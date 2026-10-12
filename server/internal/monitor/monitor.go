// Package monitor is the poller: it reads usage from GraphQL Analytics on a
// schedule, keeps hourly history in the store, builds the summary the app shows,
// fires alerts and answers the API's detail queries.
package monitor

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/pablo-collantes/cfusage/server/internal/cf"
	"github.com/pablo-collantes/cfusage/server/internal/killswitch"
	"github.com/pablo-collantes/cfusage/server/internal/model"
	"github.com/pablo-collantes/cfusage/server/internal/store"
	"github.com/pablo-collantes/cfusage/server/internal/usage"
)

type Config struct {
	Account         string
	ZoneIDs         []string
	PollEvery       time.Duration
	DefaultCycleDay int
}

// Publisher sends an event to every connected app.
type Publisher func(event string, v any)

type Monitor struct {
	cfg     Config
	read    *cf.Client
	store   *store.Store
	Kill    *killswitch.Service
	log     *slog.Logger
	publish Publisher

	mu           sync.RWMutex
	settings     model.Settings
	inv          *Inventory
	totals       map[string]map[string]float64 // metric -> resource -> cycle to date
	storage      map[string]usage.Storage
	fresh        map[string]time.Time
	summary      model.Summary
	lastPoll     time.Time // last successful poll
	pollErr      error
	failingSince time.Time
	started      time.Time
	knownHosts   map[string]string // host -> zone name, from the sites list

	cacheMu sync.Mutex
	cache   map[string]cacheEntry

	pollNow chan struct{}
}

type cacheEntry struct {
	at time.Time
	v  any
}

func New(cfg Config, read *cf.Client, st *store.Store, log *slog.Logger, publish Publisher) *Monitor {
	if cfg.PollEvery == 0 {
		cfg.PollEvery = time.Minute
	}
	return &Monitor{
		cfg: cfg, read: read, store: st, log: log, publish: publish,
		settings:   model.DefaultSettings(cfg.DefaultCycleDay),
		totals:     map[string]map[string]float64{},
		storage:    map[string]usage.Storage{},
		fresh:      map[string]time.Time{},
		knownHosts: map[string]string{},
		cache:      map[string]cacheEntry{},
		pollNow:    make(chan struct{}, 1),
		started:    time.Now(),
	}
}

const (
	settingsKey = "settings"
	summaryKey  = "summary"
	stateKey    = "state" // last good totals, so a restart serves numbers at once
)

type savedState struct {
	Totals   map[string]map[string]float64 `json:"totals"`
	Storage  map[string]usage.Storage      `json:"storage"`
	Fresh    map[string]time.Time          `json:"fresh"`
	LastPoll time.Time                     `json:"lastPoll"`
}

// Init loads settings and the last known state from the store.
func (m *Monitor) Init(ctx context.Context) error {
	var s model.Settings
	if ok, err := m.store.GetJSON(ctx, settingsKey, &s); err != nil {
		return err
	} else if ok {
		if s.Muted == nil {
			s.Muted = []string{}
		}
		m.settings = s
	}
	var st savedState
	if ok, err := m.store.GetJSON(ctx, stateKey, &st); err != nil {
		m.log.Warn("ignoring saved state", "err", err)
	} else if ok && st.Totals != nil {
		m.totals, m.storage, m.lastPoll = st.Totals, st.Storage, st.LastPoll
		if st.Fresh != nil {
			m.fresh = st.Fresh
		}
	}
	var sum model.Summary
	if ok, _ := m.store.GetJSON(ctx, summaryKey, &sum); ok {
		m.summary = sum
	}
	// Hourly history extracted by older maths is read from Cloudflare again.
	var v int
	if _, err := m.store.GetJSON(ctx, historyVersionKey, &v); err == nil && v != historyVersion {
		if err := m.store.ForgetFetched(ctx); err != nil {
			return err
		}
		m.log.Info("hourly history is from an older version; re-reading it", "from", v, "to", historyVersion)
		if err := m.store.PutJSON(ctx, historyVersionKey, historyVersion); err != nil {
			return err
		}
	}
	return nil
}

// historyVersion goes up whenever usage.ExtractFlow changes what it counts.
// 2: Durable Object WebSocket messages count at 20:1.
const (
	historyVersion    = 2
	historyVersionKey = "history_version"
)

func (m *Monitor) Settings() model.Settings {
	m.mu.RLock()
	defer m.mu.RUnlock()
	s := m.settings
	s.Muted = append([]string{}, s.Muted...)
	return s
}

// SetSettings validates, saves and applies settings, then re-polls.
func (m *Monitor) SetSettings(ctx context.Context, s model.Settings) (model.Settings, error) {
	if s.CycleDay < 1 || s.CycleDay > 28 {
		return s, errors.New("cycleDay must be 1-28")
	}
	t := s.Thresholds
	if !(0 < t.Info && t.Info < t.Warn && t.Warn < t.Critical && t.Critical <= 1) {
		return s, errors.New("thresholds must satisfy 0 < info < warn < critical <= 1")
	}
	if s.SpikeFactor < 1 {
		return s, errors.New("spikeFactor must be at least 1")
	}
	if s.SpikeMinShare < 0 || s.SpikeMinShare > 1 {
		return s, errors.New("spikeMinShare must be 0-1")
	}
	if s.Muted == nil {
		s.Muted = []string{}
	}
	for _, k := range s.Muted {
		if _, ok := usage.Lookup(k); !ok {
			return s, fmt.Errorf("unknown metric %q in muted", k)
		}
	}
	if err := m.store.PutJSON(ctx, settingsKey, s); err != nil {
		return s, err
	}
	m.mu.Lock()
	m.settings = s
	m.mu.Unlock()
	m.TriggerPoll()
	return s, nil
}

func (m *Monitor) TriggerPoll() {
	select {
	case m.pollNow <- struct{}{}:
	default:
	}
}

// Summary is the last built summary; before the first poll, an empty one with
// pollError saying so.
func (m *Monitor) Summary() model.Summary {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.summary.GeneratedAt != "" {
		return m.summary
	}
	now := time.Now().UTC()
	start, end := usage.CycleBounds(now, m.settings.CycleDay)
	return model.Summary{
		GeneratedAt: model.TS(now), PollError: model.Str("no poll has completed yet"),
		Cycle: model.Cycle{Start: iso(start), End: iso(end), CycleDay: m.settings.CycleDay,
			Elapsed: now.Sub(start).Seconds() / end.Sub(start).Seconds(), DaysLeft: end.Sub(now).Hours() / 24},
		Status: "ok", EstimatedBillUSD: usage.BaseUSD,
		Metrics: []model.Metric{}, Alerts: []model.Alert{}, Kills: []model.Kill{},
	}
}

func (m *Monitor) Health() map[string]any {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var perr *string
	if m.pollErr != nil {
		perr = model.Str(m.pollErr.Error())
	}
	fresh := map[string]string{}
	for k, v := range m.fresh {
		fresh[k] = model.TS(v)
	}
	return map[string]any{"ok": true, "version": Version, "lastPollAt": model.TSP(m.lastPoll), "pollError": perr,
		"freshness": fresh, "dryRun": m.Kill != nil && m.Kill.DryRun}
}

var Version = "0.1.0"

func (m *Monitor) inventory() *Inventory {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.inv == nil {
		return &Inventory{Scripts: map[string]*Script{}}
	}
	return m.inv
}

// Zones is what the kill-switch service resolves hosts against.
func (m *Monitor) Zones() []killswitch.Zone { return m.inventory().Zones }

// Run polls until ctx ends.
func (m *Monitor) Run(ctx context.Context) {
	m.refreshInventory(ctx)
	m.backfill(ctx, true)
	m.poll(ctx)
	m.refreshSites(ctx)

	tick := time.NewTicker(m.cfg.PollEvery)
	defer tick.Stop()
	lastInv, lastSites, lastPrune := time.Now(), time.Now(), time.Time{}
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		case <-m.pollNow:
		}
		if time.Since(lastInv) >= 30*time.Minute {
			m.refreshInventory(ctx)
			lastInv = time.Now()
		}
		m.backfill(ctx, false)
		m.poll(ctx)
		if time.Since(lastSites) >= 5*time.Minute {
			m.refreshSites(ctx)
			lastSites = time.Now()
		}
		if time.Since(lastPrune) >= 24*time.Hour {
			if err := m.store.PruneHourly(ctx, time.Now().AddDate(0, 0, -100)); err != nil {
				m.log.Warn("prune", "err", err)
			}
			lastPrune = time.Now()
		}
	}
}

func (m *Monitor) refreshInventory(ctx context.Context) {
	inv, err := m.loadInventory(ctx)
	if err != nil {
		m.log.Warn("inventory refresh failed; keeping the previous one", "err", err)
		return
	}
	m.mu.Lock()
	m.inv = inv
	m.mu.Unlock()
	m.log.Info("inventory", "zones", len(inv.Zones), "scripts", len(inv.Scripts), "domains", len(inv.Domains), "queues", len(inv.Queues))
}

func (m *Monitor) refreshSites(ctx context.Context) {
	rows, err := m.sitesFresh(ctx, "24h")
	if err != nil {
		m.log.Warn("sites", "err", err)
		return
	}
	hosts := map[string]string{}
	for _, r := range rows {
		hosts[r.Host] = r.Zone
	}
	m.mu.Lock()
	m.knownHosts = hosts
	m.mu.Unlock()
}

func iso(t time.Time) string { return t.UTC().Format(time.RFC3339) }

// queryName is the operation name of a query, for logs.
func queryName(q string) string {
	name, _, _ := strings.Cut(strings.TrimPrefix(q, "query "), "(")
	return name
}

func truncHour(t time.Time) time.Time { return t.UTC().Truncate(time.Hour) }

func truncDay(t time.Time) time.Time {
	t = t.UTC()
	return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)
}

func day(t time.Time) string { return t.UTC().Format("2006-01-02") }

type accountResult struct {
	Viewer struct {
		Accounts []usage.AccountData `json:"accounts"`
	} `json:"viewer"`
}

// queryAccount runs an account query and returns the first account's data.
func (m *Monitor) queryAccount(ctx context.Context, q string, vars map[string]any) (usage.AccountData, error) {
	vars["acct"] = m.cfg.Account
	var r accountResult
	t0 := time.Now()
	err := m.read.GraphQL(ctx, q, vars, &r)
	m.log.Debug("graphql", "query", queryName(q), "start", vars["start"], "took", time.Since(t0).Round(time.Millisecond), "err", err)
	if err != nil {
		return nil, err
	}
	if len(r.Viewer.Accounts) == 0 {
		return nil, errors.New("graphql: account not visible to the read token")
	}
	d := r.Viewer.Accounts[0]
	for alias, rows := range d {
		if len(rows) >= usage.Limit {
			m.log.Warn("dataset hit the row limit; totals may be short", "dataset", alias, "rows", len(rows))
		}
	}
	return d, nil
}

// fetchHourly re-reads [from, to) grouped by hour and replaces the stored rows.
func (m *Monitor) fetchHourly(ctx context.Context, from, to time.Time) error {
	d, err := m.queryAccount(ctx, usage.AccountQuery("datetimeHour", false, false),
		map[string]any{"start": iso(from), "end": iso(to)})
	if err != nil {
		return err
	}
	names := m.inventory().Names
	pts := usage.ExtractFlow(d, &names, "datetimeHour")
	return m.store.ReplaceHourly(ctx, from, to, pts)
}

// backfill fetches, a day at a time, every day of the cycle and the last 8 days
// that hasn't been read since it ended (plus a 2 h margin for late data). At
// startup it catches up on everything; afterwards one day per poll at most.
func (m *Monitor) backfill(ctx context.Context, all bool) {
	now := time.Now().UTC()
	start, _ := usage.CycleBounds(now, m.Settings().CycleDay)
	from := truncDay(now).AddDate(0, 0, -8)
	if start.Before(from) {
		from = start
	}
	fetched, err := m.store.FetchedDays(ctx)
	if err != nil {
		m.log.Warn("backfill", "err", err)
		return
	}
	n := 0
	for d := from; d.Before(truncDay(now).AddDate(0, 0, -1)); d = d.AddDate(0, 0, 1) {
		end := d.AddDate(0, 0, 1)
		if at, ok := fetched[day(d)]; ok && !at.Before(end.Add(2*time.Hour)) {
			continue
		}
		if err := m.fetchHourly(ctx, d, end); err != nil {
			m.log.Warn("backfill", "day", day(d), "err", err)
			return
		}
		if err := m.store.MarkFetched(ctx, now, day(d)); err != nil {
			m.log.Warn("backfill", "err", err)
		}
		n++
		if !all {
			break
		}
	}
	if n > 0 {
		m.log.Info("backfilled hourly history", "days", n)
	}
}

func (m *Monitor) poll(ctx context.Context) {
	t0 := time.Now()
	callsBefore := m.read.Calls()
	err := m.pollOnce(ctx, t0.UTC())
	if ctx.Err() != nil {
		return // shutting down; not a failed poll
	}
	took := time.Since(t0)
	calls := m.read.Calls() - callsBefore
	if lerr := m.store.LogPoll(ctx, t0, took, calls, err); lerr != nil {
		m.log.Warn("poll log", "err", lerr)
	}

	m.mu.Lock()
	recovered := false
	if err != nil {
		m.pollErr = err
		if m.failingSince.IsZero() {
			m.failingSince = t0
		}
	} else {
		recovered = m.pollErr != nil || !m.failingSince.IsZero()
		m.pollErr, m.failingSince = nil, time.Time{}
		m.lastPoll = t0.UTC()
	}
	m.mu.Unlock()
	if err != nil {
		m.log.Warn("poll failed", "err", err, "took", took.Round(time.Millisecond))
	} else {
		m.log.Debug("poll", "took", took.Round(time.Millisecond), "calls", calls)
	}
	if recovered {
		_ = m.store.AckAlertsOfKind(ctx, "monitor", time.Now())
	}
	m.rebuild(ctx, time.Now().UTC(), true)
}

// pollOnce reads cycle-to-date totals (with storage and freshness) and the last
// 48 hours by hour. Late data is common, so both windows are re-read every time.
func (m *Monitor) pollOnce(ctx context.Context, now time.Time) error {
	start, _ := usage.CycleBounds(now, m.Settings().CycleDay)
	nowMin := now.Truncate(time.Minute)
	d, err := m.queryAccount(ctx, usage.AccountQuery("", true, true), map[string]any{
		"start": iso(start), "end": iso(nowMin.Add(time.Minute)),
		"fstart": iso(nowMin.Add(-6 * time.Hour)), "fend": iso(nowMin.Add(time.Minute)),
	})
	if err != nil {
		return err
	}
	inv := m.inventory()
	names := inv.Names
	totals := map[string]map[string]float64{}
	for _, p := range usage.ExtractFlow(d, &names, "") {
		if totals[p.Metric] == nil {
			totals[p.Metric] = map[string]float64{}
		}
		totals[p.Metric][p.Resource] += p.Value
	}
	storage := usage.ExtractStorage(d, &names)
	fresh := map[string]time.Time{}
	for _, ds := range usage.FreshDatasets {
		if rows := d["fresh_"+ds]; len(rows) > 0 {
			if t, err := time.Parse(time.RFC3339, rows[0].Dim("datetimeMinute")); err == nil {
				fresh[ds] = t
			}
		}
	}

	hFrom, hTo := truncHour(now).Add(-47*time.Hour), truncHour(now).Add(time.Hour)
	if err := m.fetchHourly(ctx, hFrom, hTo); err != nil {
		return fmt.Errorf("hourly: %w", err)
	}
	// The window covers all of yesterday and today.
	if err := m.store.MarkFetched(ctx, now, day(now.AddDate(0, 0, -1)), day(now)); err != nil {
		return err
	}

	m.mu.Lock()
	m.totals, m.storage, m.fresh = totals, storage, fresh
	m.mu.Unlock()
	return m.store.PutJSON(ctx, stateKey, savedState{Totals: totals, Storage: storage, Fresh: fresh, LastPoll: now})
}

// dataAsOf is the oldest newest-minute among the key datasets that have had data
// in the last 30 minutes. Idle datasets are just idle, not late.
func dataAsOf(fresh map[string]time.Time, now time.Time) time.Time {
	var oldest, newest time.Time
	for _, ds := range usage.KeyFreshDatasets {
		t, ok := fresh[ds]
		if !ok {
			continue
		}
		if t.After(newest) {
			newest = t
		}
		if now.Sub(t) <= 30*time.Minute && (oldest.IsZero() || t.Before(oldest)) {
			oldest = t
		}
	}
	if oldest.IsZero() {
		for _, t := range fresh {
			if t.After(newest) {
				newest = t
			}
		}
		return newest
	}
	return oldest
}

// rebuild recomputes the summary from the latest totals and stored history,
// fires alerts when evaluate is set, and publishes it.
func (m *Monitor) rebuild(ctx context.Context, now time.Time, evaluate bool) {
	sum, hourly, err := m.buildSummary(ctx, now)
	if err != nil {
		m.log.Error("building summary", "err", err)
		return
	}
	if evaluate {
		for _, a := range m.evaluateAlerts(ctx, now, sum, hourly) {
			m.log.Info("alert", "id", a.ID, "title", a.Title)
			m.publish("alert", a)
		}
	}
	m.fillLists(ctx, &sum)
	roundForClients(&sum)
	m.mu.Lock()
	m.summary = sum
	m.mu.Unlock()
	if err := m.store.PutJSON(ctx, summaryKey, sum); err != nil {
		m.log.Warn("saving summary", "err", err)
	}
	m.publish("summary", sum)
}

// RefreshLists updates the summary's alerts and kills (after an ack or a kill
// state change) without re-polling, and republishes it.
func (m *Monitor) RefreshLists(ctx context.Context) {
	m.mu.RLock()
	sum := m.summary
	m.mu.RUnlock()
	if sum.GeneratedAt == "" {
		return // nothing built yet; the first poll will include the lists
	}
	m.fillLists(ctx, &sum)
	sum.GeneratedAt = model.TS(time.Now())
	m.mu.Lock()
	m.summary = sum
	m.mu.Unlock()
	m.publish("summary", sum)
}

func (m *Monitor) fillLists(ctx context.Context, sum *model.Summary) {
	alerts, err := m.store.Alerts(ctx, 20, true)
	if err != nil {
		m.log.Warn("alerts", "err", err)
		alerts = []model.Alert{}
	}
	kills, err := m.store.OpenKills(ctx, time.Now().Add(-24*time.Hour))
	if err != nil {
		m.log.Warn("kills", "err", err)
		kills = []model.Kill{}
	}
	sum.Alerts, sum.Kills = alerts, kills
}

// hourlySeries is the stored history by metric: hour -> total, and hour -> resource -> amount.
type hourlySeries struct {
	total map[string]map[time.Time]float64
	byRes map[string]map[time.Time]map[string]float64
}

func (h hourlySeries) sum(metric string, from, to time.Time) float64 {
	t := 0.0
	for hr, v := range h.total[metric] {
		if !hr.Before(from) && hr.Before(to) {
			t += v
		}
	}
	return t
}

func (m *Monitor) loadHourly(ctx context.Context, from, to time.Time, metrics ...string) (hourlySeries, error) {
	rows, err := m.store.Hourly(ctx, from, to, metrics...)
	if err != nil {
		return hourlySeries{}, err
	}
	h := hourlySeries{total: map[string]map[time.Time]float64{}, byRes: map[string]map[time.Time]map[string]float64{}}
	for _, r := range rows {
		if h.total[r.Metric] == nil {
			h.total[r.Metric] = map[time.Time]float64{}
			h.byRes[r.Metric] = map[time.Time]map[string]float64{}
		}
		h.total[r.Metric][r.Hour] += r.Value
		if h.byRes[r.Metric][r.Hour] == nil {
			h.byRes[r.Metric][r.Hour] = map[string]float64{}
		}
		h.byRes[r.Metric][r.Hour][r.Resource] += r.Value
	}
	return h, nil
}

func topN(by map[string]float64, n int) []model.NameValue {
	out := []model.NameValue{}
	for k, v := range by {
		if v > 0 {
			out = append(out, model.NameValue{Name: k, Value: v})
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Value != out[j].Value {
			return out[i].Value > out[j].Value
		}
		return out[i].Name < out[j].Name
	})
	if n > 0 && len(out) > n {
		out = out[:n]
	}
	return out
}

func (m *Monitor) buildSummary(ctx context.Context, now time.Time) (model.Summary, hourlySeries, error) {
	m.mu.RLock()
	settings := m.settings
	totals, storage, fresh := m.totals, m.storage, m.fresh
	lastPoll, pollErr := m.lastPoll, m.pollErr
	m.mu.RUnlock()

	start, end := usage.CycleBounds(now, settings.CycleDay)
	elapsed := now.Sub(start).Seconds() / end.Sub(start).Seconds()
	from := start
	if f := truncHour(now).Add(-26 * time.Hour); f.Before(from) {
		from = f
	}
	hourly, err := m.loadHourly(ctx, from, truncHour(now).Add(time.Hour))
	if err != nil {
		return model.Summary{}, hourly, err
	}

	sum := model.Summary{
		GeneratedAt: model.TS(now),
		LastPollAt:  model.TSP(lastPoll),
		Cycle: model.Cycle{Start: iso(start), End: iso(end), CycleDay: settings.CycleDay,
			Elapsed: elapsed, DaysLeft: end.Sub(now).Hours() / 24},
		Status:           "ok",
		EstimatedBillUSD: usage.BaseUSD,
		Metrics:          []model.Metric{},
	}
	if pollErr != nil {
		sum.PollError = model.Str(pollErr.Error())
	}
	if t := dataAsOf(fresh, now); !t.IsZero() {
		sum.DataAsOf = model.TSP(t)
	}

	last24From, last24To := truncHour(now).Add(-24*time.Hour), truncHour(now)
	for _, def := range usage.Defs {
		mt := model.Metric{Key: def.Key, Label: def.Label, Group: def.Group, Unit: def.Unit, Kind: def.Kind,
			Included: def.Included, Price: def.Price, Sampled: def.Sampled, Daily: []model.DateValue{}}
		if def.Kind == "flow" {
			for _, v := range totals[def.Key] {
				mt.Used += v
			}
			mt.Top = topN(totals[def.Key], 5)
			mt.Rate24h = hourly.sum(def.Key, last24From, last24To)
			perDay := map[string]float64{}
			for hr, v := range hourly.total[def.Key] {
				if !hr.Before(start) {
					perDay[day(hr)] += v
				}
			}
			var complete []float64 // whole UTC days of this cycle, oldest first
			for d := start; !d.After(now); d = d.AddDate(0, 0, 1) {
				mt.Daily = append(mt.Daily, model.DateValue{Date: day(d), Value: perDay[day(d)]})
				if !d.AddDate(0, 0, 1).After(now) {
					complete = append(complete, perDay[day(d)])
				}
			}
			mt.Projected = usage.ProjectFlow(mt.Used, complete, start, end, now)
		} else {
			st := storage[def.Key]
			// Used is the size now, so "4.9 GB of 10 GB" reads the way the dashboard
			// does; Projected is what the cycle bills (the average of daily peaks).
			mt.Used = st.Current()
			mt.Rate24h = st.Current()
			mt.Projected = usage.ProjectStorage(st.SumPeaks(start), st.DaysSeen(start), st.Current(), start, end)
			mt.Top = topN(st.Latest, 5)
			days := make([]string, 0, len(st.PerDay))
			for d := range st.PerDay {
				days = append(days, d)
			}
			sort.Strings(days)
			for _, d := range days {
				mt.Daily = append(mt.Daily, model.DateValue{Date: d, Value: st.PerDay[d]})
			}
		}
		mt.Pct = mt.Used / def.Included
		mt.ProjectedPct = mt.Projected / def.Included
		mt.Status = usage.Status(mt.Pct, mt.ProjectedPct, elapsed, settings)
		if over := mt.Projected - def.Included; over > 0 {
			mt.Overage = model.Overage{Units: over, USD: over * def.PerUnit}
		}
		sum.EstimatedBillUSD += mt.Overage.USD
		sum.Status = usage.Worse(sum.Status, mt.Status)
		sum.Metrics = append(sum.Metrics, mt)
	}
	return sum, hourly, nil
}

// openKillIDs are the ids of kills applied now.
func (m *Monitor) openKillIDs(ctx context.Context) map[string]bool {
	out := map[string]bool{}
	kills, err := m.store.OpenKills(ctx, time.Now())
	if err != nil {
		return out
	}
	for _, k := range kills {
		if k.State != model.KillFailed {
			out[k.ID] = true
		}
	}
	return out
}

// AvailableKills lists every kill switch the app may apply: a WAF block for every
// host with traffic or a custom domain, and every script's and queue's switches.
func (m *Monitor) AvailableKills(ctx context.Context) []model.KillTarget {
	inv := m.inventory()
	open := m.openKillIDs(ctx)
	var out []model.KillTarget
	names := make([]string, 0, len(inv.Scripts))
	for n := range inv.Scripts {
		names = append(names, n)
	}
	sort.Strings(names)
	for _, n := range names {
		out = append(out, inv.scriptKills(n, open)...)
	}
	m.mu.RLock()
	hosts := make([]string, 0, len(m.knownHosts))
	for h := range m.knownHosts {
		hosts = append(hosts, h)
	}
	m.mu.RUnlock()
	sort.Strings(hosts)
	for _, h := range hosts {
		if _, ok := inv.zoneFor(h); ok && killswitch.Validate(model.KindWAFBlock, h) == nil {
			out = append(out, killswitch.Target(model.KindWAFBlock, h, ""))
		}
	}
	for _, q := range inv.Queues {
		out = append(out, inv.queueKill(q))
	}
	return dedupe(out, 0)
}

// KillTargetFor finds an available target, so the API only applies kills the
// server itself offered (or ones already applied).
func (m *Monitor) KillTargetFor(ctx context.Context, kind, target string) (model.KillTarget, bool) {
	for _, t := range m.AvailableKills(ctx) {
		if t.Kind == kind && t.Target == target {
			return t, true
		}
	}
	return model.KillTarget{}, false
}

// OnKill is the kill service's event hook.
func (m *Monitor) OnKill(k model.Kill) {
	m.publish("kill", k)
	m.RefreshLists(context.Background())
}

func (m *Monitor) cached(key string, ttl time.Duration, f func() (any, error)) (any, error) {
	m.cacheMu.Lock()
	e, ok := m.cache[key]
	m.cacheMu.Unlock()
	if ok && time.Since(e.at) < ttl {
		return e.v, nil
	}
	v, err := f()
	if err != nil {
		return nil, err
	}
	m.cacheMu.Lock()
	m.cache[key] = cacheEntry{time.Now(), v}
	for k, e := range m.cache { // keep the cache from growing without bound
		if time.Since(e.at) > 10*time.Minute {
			delete(m.cache, k)
		}
	}
	m.cacheMu.Unlock()
	return v, nil
}

// clone deep-copies through JSON so callers can't mutate cached values.
func clone[T any](v T) T {
	b, _ := json.Marshal(v)
	var out T
	_ = json.Unmarshal(b, &out)
	return out
}
