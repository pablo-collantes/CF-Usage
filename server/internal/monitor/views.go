package monitor

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/pablo-collantes/cfusage/server/internal/killswitch"
	"github.com/pablo-collantes/cfusage/server/internal/model"
	"github.com/pablo-collantes/cfusage/server/internal/usage"
)

// ErrBadRequest and ErrNotFound are mapped to 400 and 404 by the API.
var (
	ErrBadRequest = errors.New("bad request")
	ErrNotFound   = errors.New("not found")
)

// span is a range cut into equal buckets, the last of which ends after now.
type span struct {
	bucket string // API name: minute5 | hour | day
	dim    string // GraphQL dimension
	step   time.Duration
	start  time.Time
	n      int
}

func (s span) end() time.Time { return s.start.Add(time.Duration(s.n) * s.step) }

func (s span) index(t time.Time) int {
	if t.Before(s.start) {
		return -1
	}
	i := int(t.Sub(s.start) / s.step)
	if i >= s.n {
		return -1
	}
	return i
}

func (s span) times() []string {
	out := make([]string, s.n)
	for i := range out {
		out[i] = iso(s.start.Add(time.Duration(i) * s.step))
	}
	return out
}

func newSpan(bucket string, now time.Time, n int) span {
	switch bucket {
	case "minute5":
		return span{bucket, "datetimeFiveMinutes", 5 * time.Minute, now.UTC().Truncate(5 * time.Minute).Add(-time.Duration(n-1) * 5 * time.Minute), n}
	case "hour":
		return span{bucket, "datetimeHour", time.Hour, truncHour(now).Add(-time.Duration(n-1) * time.Hour), n}
	}
	return span{"day", "date", 24 * time.Hour, truncDay(now).AddDate(0, 0, -(n - 1)), n}
}

// usageSpan is a metric/workers range: 24h and 7d by hour, cycle by day.
func (m *Monitor) usageSpan(rng string, now time.Time) (span, error) {
	switch rng {
	case "", "24h":
		return newSpan("hour", now, 24), nil
	case "7d":
		return newSpan("hour", now, 7*24), nil
	case "cycle":
		start, _ := usage.CycleBounds(now, m.Settings().CycleDay)
		return span{"day", "date", 24 * time.Hour, start, int(truncDay(now).Sub(start).Hours()/24) + 1}, nil
	}
	return span{}, fmt.Errorf("%w: range must be 24h, 7d or cycle", ErrBadRequest)
}

// siteSpan is a zone traffic range. 30 days by day stays inside the zone
// dataset's 30-day maximum query window.
func siteSpan(rng string, now time.Time) (span, error) {
	switch rng {
	case "1h":
		return newSpan("minute5", now, 12), nil
	case "", "24h":
		return newSpan("hour", now, 24), nil
	case "7d":
		return newSpan("hour", now, 7*24), nil
	case "30d":
		return newSpan("day", now, 30), nil
	}
	return span{}, fmt.Errorf("%w: range must be 1h, 24h, 7d or 30d", ErrBadRequest)
}

// bucketed sums stored hourly rows into the span's buckets: metric -> resource -> per bucket.
func (m *Monitor) bucketed(ctx context.Context, s span, metrics ...string) (map[string]map[string][]float64, error) {
	rows, err := m.store.Hourly(ctx, s.start, s.end(), metrics...)
	if err != nil {
		return nil, err
	}
	out := map[string]map[string][]float64{}
	for _, r := range rows {
		i := s.index(r.Hour)
		if i < 0 {
			continue
		}
		if out[r.Metric] == nil {
			out[r.Metric] = map[string][]float64{}
		}
		if out[r.Metric][r.Resource] == nil {
			out[r.Metric][r.Resource] = make([]float64, s.n)
		}
		out[r.Metric][r.Resource][i] += r.Value
	}
	return out, nil
}

func total(v []float64) float64 {
	t := 0.0
	for _, x := range v {
		t += x
	}
	return t
}

func addInto(dst, src []float64) {
	for i := range src {
		dst[i] += src[i]
	}
}

// --- metrics ---

func (m *Monitor) MetricDetail(ctx context.Context, key, rng string) (model.MetricDetail, error) {
	def, ok := usage.Lookup(key)
	if !ok {
		return model.MetricDetail{}, fmt.Errorf("%w: no metric %q", ErrNotFound, key)
	}
	var mt model.Metric
	for _, x := range m.Summary().Metrics {
		if x.Key == key {
			mt = x
		}
	}
	if mt.Key == "" {
		return model.MetricDetail{}, fmt.Errorf("%w: no data yet", ErrNotFound)
	}
	now := time.Now().UTC()
	s, err := m.usageSpan(rng, now)
	if err != nil {
		return model.MetricDetail{}, err
	}
	if rng == "" {
		rng = "24h"
	}
	d := model.MetricDetail{Metric: mt, Range: rng, Bucket: s.bucket, Series: []model.TimeValue{},
		Cumulative: []model.TimeValue{}, ByResource: []model.ResourceShare{}}

	if def.Kind == "storage" {
		// Storage is a daily gauge, so every range is by day.
		m.mu.RLock()
		st := m.storage[key]
		m.mu.RUnlock()
		days := map[string]bool{}
		for _, dv := range mt.Daily {
			days[dv.Date] = true
		}
		cut := ""
		if rng != "cycle" {
			cut = day(s.start)
		}
		d.Bucket = "day"
		for _, dv := range mt.Daily {
			if dv.Date >= cut {
				d.Series = append(d.Series, model.TimeValue{T: dv.Date + "T00:00:00Z", Value: dv.Value})
			}
		}
		d.ByResource = shares(st.Latest)
		return d, nil
	}

	var by map[string]float64
	series := make([]float64, s.n)
	if rng == "cycle" {
		// Daily sums of the hourly history for the chart, the cycle totals for the shares.
		b, err := m.bucketed(ctx, s, key)
		if err != nil {
			return d, err
		}
		for _, v := range b[key] {
			addInto(series, v)
		}
		m.mu.RLock()
		by = m.totals[key]
		m.mu.RUnlock()
		run := 0.0
		for i, t := range s.times() {
			run += series[i]
			d.Cumulative = append(d.Cumulative, model.TimeValue{T: t, Value: run})
		}
	} else {
		b, err := m.bucketed(ctx, s, key)
		if err != nil {
			return d, err
		}
		by = map[string]float64{}
		for res, v := range b[key] {
			addInto(series, v)
			by[res] = total(v)
		}
	}
	for i, t := range s.times() {
		d.Series = append(d.Series, model.TimeValue{T: t, Value: series[i]})
	}
	d.ByResource = shares(by)
	return d, nil
}

func shares(by map[string]float64) []model.ResourceShare {
	t := 0.0
	for _, v := range by {
		t += v
	}
	out := []model.ResourceShare{}
	for _, nv := range topN(by, 0) {
		s := 0.0
		if t > 0 {
			s = nv.Value / t
		}
		out = append(out, model.ResourceShare{Name: nv.Name, Value: nv.Value, Share: s})
	}
	return out
}

// --- workers ---

var workerMetrics = []string{"workers_requests", "workers_cpu_ms", usage.AuxErrors, usage.AuxSubrequests}

type workerData struct {
	s       span
	series  map[string]map[string][]float64 // metric -> script -> per bucket
	totals  map[string]map[string]float64   // metric -> script, over the range
	allReqs float64
}

func (m *Monitor) workerData(ctx context.Context, rng string) (workerData, error) {
	now := time.Now().UTC()
	s, err := m.usageSpan(rng, now)
	if err != nil {
		return workerData{}, err
	}
	b, err := m.bucketed(ctx, s, workerMetrics...)
	if err != nil {
		return workerData{}, err
	}
	w := workerData{s: s, series: b, totals: map[string]map[string]float64{}}
	if rng == "cycle" {
		m.mu.RLock()
		for _, k := range workerMetrics {
			w.totals[k] = m.totals[k]
		}
		m.mu.RUnlock()
	} else {
		for _, k := range workerMetrics {
			w.totals[k] = map[string]float64{}
			for res, v := range b[k] {
				w.totals[k][res] = total(v)
			}
		}
	}
	for _, v := range w.totals["workers_requests"] {
		w.allReqs += v
	}
	return w, nil
}

func (m *Monitor) workerRow(w workerData, name string, inv *Inventory, open map[string]bool) model.WorkerRow {
	r := model.WorkerRow{Name: name, Kind: "worker", Hosts: []string{}, Killable: []model.KillTarget{}}
	if strings.HasPrefix(name, usage.PagesPrefix) {
		r.Kind = "pages"
	} else {
		r.Hosts = inv.hostsOf(name)
		r.Killable = inv.scriptKills(name, open)
	}
	r.Requests = w.totals["workers_requests"][name]
	r.Errors = w.totals[usage.AuxErrors][name]
	r.CPUMs = w.totals["workers_cpu_ms"][name]
	r.Subrequests = w.totals[usage.AuxSubrequests][name]
	if r.Requests > 0 {
		r.ErrorRate = r.Errors / r.Requests
		r.CPUMsPerRequest = r.CPUMs / r.Requests
	}
	if w.allReqs > 0 {
		r.ShareOfRequests = r.Requests / w.allReqs
	}
	spark := w.series["workers_requests"][name]
	if spark == nil {
		spark = make([]float64, w.s.n)
	}
	n := 24
	if w.s.n == 7*24 {
		n = 28 // one point per 6 hours
	} else if w.s.bucket == "day" {
		n = w.s.n
	}
	r.Spark = usage.Downsample(spark, n)
	return r
}

func (m *Monitor) Workers(ctx context.Context, rng string) ([]model.WorkerRow, error) {
	w, err := m.workerData(ctx, rng)
	if err != nil {
		return nil, err
	}
	inv := m.inventory()
	open := m.openKillIDs(ctx)
	out := []model.WorkerRow{}
	for name, v := range w.totals["workers_requests"] {
		if v > 0 {
			out = append(out, m.workerRow(w, name, inv, open))
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Requests != out[j].Requests {
			return out[i].Requests > out[j].Requests
		}
		return out[i].Name < out[j].Name
	})
	return out, nil
}

func (m *Monitor) WorkerDetail(ctx context.Context, name, rng string) (model.WorkerDetail, error) {
	w, err := m.workerData(ctx, rng)
	if err != nil {
		return model.WorkerDetail{}, err
	}
	inv := m.inventory()
	_, known := inv.Scripts[name]
	if !known && w.totals["workers_requests"][name] == 0 {
		return model.WorkerDetail{}, fmt.Errorf("%w: no script %q", ErrNotFound, name)
	}
	open := m.openKillIDs(ctx)
	d := model.WorkerDetail{Row: m.workerRow(w, name, inv, open), Bucket: w.s.bucket,
		Series: []model.WorkerPoint{}, Colos: []model.ColoCount{}}
	get := func(metric string, i int) float64 {
		if v := w.series[metric][name]; v != nil {
			return v[i]
		}
		return 0
	}
	for i, t := range w.s.times() {
		d.Series = append(d.Series, model.WorkerPoint{T: t, Requests: get("workers_requests", i),
			Errors: get(usage.AuxErrors, i), CPUMs: get("workers_cpu_ms", i)})
	}
	if d.Row.Kind == "worker" {
		colos, err := m.colos(ctx, name, w.s)
		if err != nil {
			m.log.Warn("colos", "script", name, "err", err)
		} else {
			d.Colos = colos
		}
		siteRange := map[string]string{"": "24h", "24h": "24h", "7d": "7d", "cycle": "30d"}[rng]
		for _, h := range d.Row.Hosts {
			if _, ok := inv.zoneFor(h); ok {
				sd, err := m.SiteDetail(ctx, h, siteRange)
				if err != nil {
					m.log.Warn("worker site", "host", h, "err", err)
				} else {
					d.Site = &sd
				}
				break
			}
		}
	}
	return d, nil
}

func (m *Monitor) colos(ctx context.Context, script string, s span) ([]model.ColoCount, error) {
	key := fmt.Sprintf("colos|%s|%s|%d", script, iso(s.start), s.n)
	v, err := m.cached(key, time.Minute, func() (any, error) {
		d, err := m.queryAccount(ctx, usage.ColoQuery, map[string]any{"script": script,
			"start": iso(s.start), "end": iso(time.Now().UTC().Truncate(time.Minute).Add(time.Minute))})
		if err != nil {
			return nil, err
		}
		out := []model.ColoCount{}
		for _, r := range d["colos"] {
			out = append(out, model.ColoCount{Colo: r.Dim("coloCode"), Requests: r.Sum["requests"]})
		}
		return out, nil
	})
	if err != nil {
		return nil, err
	}
	return clone(v.([]model.ColoCount)), nil
}

// --- sites ---

type zoneResult struct {
	Viewer struct {
		Zones []usage.AccountData `json:"zones"`
	} `json:"viewer"`
}

func (m *Monitor) queryZone(ctx context.Context, zone, q string, vars map[string]any) (usage.AccountData, error) {
	vars["zone"] = zone
	var r zoneResult
	t0 := time.Now()
	err := m.read.GraphQL(ctx, q, vars, &r)
	m.log.Debug("graphql", "query", queryName(q), "start", vars["start"], "took", time.Since(t0).Round(time.Millisecond), "err", err)
	if err != nil {
		return nil, err
	}
	if len(r.Viewer.Zones) == 0 {
		return nil, fmt.Errorf("graphql: zone %s not visible to the read token", zone)
	}
	for alias, rows := range r.Viewer.Zones[0] {
		if len(rows) >= usage.Limit {
			m.log.Warn("zone dataset hit the row limit", "dataset", alias, "rows", len(rows))
		}
	}
	return r.Viewer.Zones[0], nil
}

// zoneWindow is the query window of a span. The end is capped at the next
// minute, and the span never exceeds the zone dataset's 30-day maximum.
func zoneWindow(s span) map[string]any {
	end := time.Now().UTC().Truncate(time.Minute).Add(time.Minute)
	if s.end().Before(end) {
		end = s.end()
	}
	return map[string]any{"start": iso(s.start), "end": iso(end)}
}

func parseBucket(s span, v string) (time.Time, bool) {
	layout := time.RFC3339
	if s.bucket == "day" {
		layout = "2006-01-02"
	}
	t, err := time.Parse(layout, v)
	return t, err == nil
}

// Sites lists hostnames with visitor traffic, cached for a minute.
func (m *Monitor) Sites(ctx context.Context, rng string) ([]model.SiteRow, error) {
	if rng == "" {
		rng = "24h"
	}
	v, err := m.cached("sites|"+rng, time.Minute, func() (any, error) { return m.sitesFresh(ctx, rng) })
	if err != nil {
		return nil, err
	}
	return clone(v.([]model.SiteRow)), nil
}

func (m *Monitor) sitesFresh(ctx context.Context, rng string) ([]model.SiteRow, error) {
	s, err := siteSpan(rng, time.Now())
	if err != nil {
		return nil, err
	}
	inv := m.inventory()
	if len(inv.Zones) == 0 {
		return nil, errors.New("no zones in the inventory yet")
	}
	out := []model.SiteRow{}
	for _, z := range inv.Zones {
		d, err := m.queryZone(ctx, z.ID, usage.SitesQuery, zoneWindow(s))
		if err != nil {
			return nil, err
		}
		// Sparklines for the busiest hosts only, so hosts x buckets fits one query.
		var top []string
		for _, r := range d["hosts"] {
			if len(top) < min(100, usage.Limit/s.n) {
				top = append(top, r.Dim("clientRequestHTTPHost"))
			}
		}
		if len(top) > 0 {
			vars := zoneWindow(s)
			vars["hosts"] = top
			sp, err := m.queryZone(ctx, z.ID, usage.SparkQuery(s.dim), vars)
			if err != nil {
				return nil, err
			}
			d["spark"] = sp["spark"]
		}
		blocked, cached := map[string]float64{}, map[string]float64{}
		for _, r := range d["blocked"] {
			blocked[r.Dim("clientRequestHTTPHost")] += r.Count
		}
		for _, r := range d["cached"] {
			cached[r.Dim("clientRequestHTTPHost")] += r.Count
		}
		spark := map[string][]float64{}
		for _, r := range d["spark"] {
			h := r.Dim("clientRequestHTTPHost")
			if t, ok := parseBucket(s, r.Dim(s.dim)); ok {
				if i := s.index(t); i >= 0 {
					if spark[h] == nil {
						spark[h] = make([]float64, s.n)
					}
					spark[h][i] += r.Count
				}
			}
		}
		for _, r := range d["hosts"] {
			h := r.Dim("clientRequestHTTPHost")
			out = append(out, m.siteRow(inv, z, h, r, blocked[h], cached[h], spark[h], s))
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Requests != out[j].Requests {
			return out[i].Requests > out[j].Requests
		}
		return out[i].Host < out[j].Host
	})
	return out, nil
}

func (m *Monitor) siteRow(inv *Inventory, z killswitch.Zone, host string, r usage.Row, blocked, cached float64, spark []float64, s span) model.SiteRow {
	if spark == nil {
		spark = make([]float64, s.n)
	}
	row := model.SiteRow{Host: host, Zone: z.Name, Requests: r.Count, Bytes: r.Sum["edgeResponseBytes"],
		Blocked: blocked, Status4xx: r.Ratio["status4xx"], Status5xx: r.Ratio["status5xx"],
		Service: inv.serviceOf(host), Spark: usage.Downsample(spark, 24), Killable: []model.KillTarget{}}
	if r.Count > 0 {
		row.CachedShare = cached / r.Count
	}
	if killswitch.Validate(model.KindWAFBlock, host) == nil {
		row.Killable = append(row.Killable, killswitch.Target(model.KindWAFBlock, host, ""))
	}
	return row
}

// SiteDetail is one host's traffic, cached for a minute per range.
func (m *Monitor) SiteDetail(ctx context.Context, host, rng string) (model.SiteDetail, error) {
	if rng == "" {
		rng = "24h"
	}
	host = strings.ToLower(host)
	inv := m.inventory()
	z, ok := inv.zoneFor(host)
	if !ok {
		return model.SiteDetail{}, fmt.Errorf("%w: %s is not on a zone this server reads", ErrNotFound, host)
	}
	s, err := siteSpan(rng, time.Now())
	if err != nil {
		return model.SiteDetail{}, err
	}
	v, err := m.cached("site|"+host+"|"+rng, time.Minute, func() (any, error) {
		vars := zoneWindow(s)
		vars["host"] = host
		d, err := m.queryZone(ctx, z.ID, usage.SiteQuery(s.dim), vars)
		if err != nil {
			return nil, err
		}
		return m.siteDetail(inv, z, host, d, s), nil
	})
	if err != nil {
		return model.SiteDetail{}, err
	}
	return clone(v.(model.SiteDetail)), nil
}

func (m *Monitor) siteDetail(inv *Inventory, z killswitch.Zone, host string, d usage.AccountData, s span) model.SiteDetail {
	req, blk := make([]float64, s.n), make([]float64, s.n)
	fill := func(rows []usage.Row, into []float64) {
		for _, r := range rows {
			if t, ok := parseBucket(s, r.Dim(s.dim)); ok {
				if i := s.index(t); i >= 0 {
					into[i] += r.Count
				}
			}
		}
	}
	fill(d["series"], req)
	fill(d["blockedSeries"], blk)

	var tot usage.Row
	if len(d["total"]) > 0 {
		tot = d["total"][0]
	}
	cached := 0.0
	for _, r := range d["cache"] {
		if r.Dim("cacheStatus") == "hit" {
			cached += r.Count
		}
	}
	out := model.SiteDetail{
		Row: m.siteRow(inv, z, host, tot, total(blk), cached, req, s), Bucket: s.bucket,
		Series: []model.SitePoint{}, Paths: []model.PathCount{}, Countries: []model.CountryCount{},
		Statuses: []model.StatusCount{}, SecurityActions: []model.ActionCount{}, UserAgents: []model.AgentCount{},
		Cache: []model.CacheCount{},
	}
	for i, t := range s.times() {
		out.Series = append(out.Series, model.SitePoint{T: t, Requests: req[i], Blocked: blk[i]})
	}
	for _, r := range d["paths"] {
		out.Paths = append(out.Paths, model.PathCount{Path: r.Dim("clientRequestPath"), Requests: r.Count})
	}
	for _, r := range d["countries"] {
		out.Countries = append(out.Countries, model.CountryCount{Country: r.Dim("clientCountryName"), Requests: r.Count})
	}
	for _, r := range d["statuses"] {
		st, _ := strconv.Atoi(r.Dim("edgeResponseStatus"))
		out.Statuses = append(out.Statuses, model.StatusCount{Status: st, Requests: r.Count})
	}
	for _, r := range d["security"] {
		a := r.Dim("securityAction")
		if a == "" {
			a = "none"
		}
		out.SecurityActions = append(out.SecurityActions, model.ActionCount{Action: a, Requests: r.Count})
	}
	for _, r := range d["agents"] {
		out.UserAgents = append(out.UserAgents, model.AgentCount{Agent: r.Dim("userAgentBrowser"), Requests: r.Count})
	}
	for _, r := range d["cache"] {
		out.Cache = append(out.Cache, model.CacheCount{Status: r.Dim("cacheStatus"), Requests: r.Count})
	}
	return out
}
