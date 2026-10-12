package monitor

import (
	"context"
	"io"
	"log/slog"
	"maps"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/pablo-collantes/cfusage/server/internal/killswitch"
	"github.com/pablo-collantes/cfusage/server/internal/model"
	"github.com/pablo-collantes/cfusage/server/internal/store"
	"github.com/pablo-collantes/cfusage/server/internal/usage"
)

func at(s string) time.Time {
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		panic(err)
	}
	return t
}

func TestThresholdAlertPicksHighestLevel(t *testing.T) {
	s := model.DefaultSettings(23)
	start, end, now := at("2026-09-23T00:00:00Z"), at("2026-10-23T00:00:00Z"), at("2026-10-06T21:00:00Z")
	mt := model.Metric{Key: "workers_requests", Label: "Workers requests", Unit: "count", Used: 9.1e6, Included: 10e6, Pct: 0.91}
	a, ok := thresholdAlert(mt, s, start, end, now, " mailbox made 61%.")
	if !ok || a.ID != "threshold:workers_requests:90:2026-09-23" || a.Severity != "critical" {
		t.Fatalf("91%%: %+v", a)
	}
	if a.Title != "Workers requests at 90%" || a.Body != "9.1M of 10M used, 17 days left. mailbox made 61%." {
		t.Errorf("text: %q / %q", a.Title, a.Body)
	}
	mt.Pct = 1.02
	if a, _ := thresholdAlert(mt, s, start, end, now, ""); a.ID != "threshold:workers_requests:100:2026-09-23" {
		t.Errorf("over: %s", a.ID)
	}
	mt.Pct = 0.3
	if _, ok := thresholdAlert(mt, s, start, end, now, ""); ok {
		t.Error("30% fired")
	}
}

func hourly(metric string, vals map[time.Time]float64) hourlySeries {
	return hourlySeries{total: map[string]map[time.Time]float64{metric: vals}}
}

func TestSpikeAlert(t *testing.T) {
	s := model.DefaultSettings(23)
	def, _ := usage.Lookup("workers_requests")
	now, end := at("2026-10-06T21:10:00Z"), at("2026-10-23T00:00:00Z")
	base := map[time.Time]float64{}
	for i := 2; i <= 25; i++ {
		base[at("2026-10-06T21:00:00Z").Add(-time.Duration(i)*time.Hour)] = 1000 // 24 h before the last full hour
	}
	mt := model.Metric{Key: def.Key, Label: def.Label, Unit: def.Unit, Used: 1e6, Included: def.Included}

	// 20k in the last full hour: 20x the average and 0.2% of included -> under spikeMinShare (1%).
	vals := maps.Clone(base)
	vals[at("2026-10-06T20:00:00Z")] = 20_000
	if _, ok := spikeAlert(mt, def, s, hourly(def.Key, vals), at("2026-10-06T20:00:00Z"), now, end); ok {
		t.Error("small spike fired")
	}
	// 200k: 200x and 2% of included. At that rate the 9M left run out in 45 h.
	vals[at("2026-10-06T20:00:00Z")] = 200_000
	a, ok := spikeAlert(mt, def, s, hourly(def.Key, vals), at("2026-10-06T20:00:00Z"), now, end)
	if !ok || a.ID != "spike:workers_requests:2026-10-06T20" || a.Severity != "critical" {
		t.Fatalf("big spike: %+v", a)
	}
	if !strings.Contains(a.Body, "200.0× the 24 h average") || !strings.Contains(a.Body, "runs out in 45 h") {
		t.Errorf("body: %s", a.Body)
	}
	// The hour in progress is judged on what it has so far.
	vals[at("2026-10-06T21:00:00Z")] = 150_000
	a, ok = spikeAlert(mt, def, s, hourly(def.Key, vals), at("2026-10-06T21:00:00Z"), now, end)
	if !ok || a.ID != "spike:workers_requests:2026-10-06T21" || !strings.Contains(a.Body, "150k so far in the hour from 21:00 UTC") {
		t.Errorf("partial hour: %+v", a)
	}
	// 4x the average isn't a spike at factor 5, however big.
	for k := range vals {
		vals[k] = 1e6
	}
	vals[at("2026-10-06T20:00:00Z")] = 4e6
	if _, ok := spikeAlert(mt, def, s, hourly(def.Key, vals), at("2026-10-06T20:00:00Z"), now, end); ok {
		t.Error("4x fired")
	}
}

func TestEvaluateAlertsFiresOnce(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	m := New(Config{Account: "a", DefaultCycleDay: 23}, nil, st, slog.New(slog.NewTextHandler(io.Discard, nil)), func(string, any) {})
	m.totals = map[string]map[string]float64{"d1_rows_written": {"maindb": 40e6, "other": 1e6}}
	m.inv = &Inventory{Scripts: map[string]*Script{"api": {Name: "api", Binds: []Binding{{"d1", "maindb"}}, CronsKnown: true}},
		Domains: []Domain{{Host: "api.example.dev", Service: "api"}}, Zones: []killswitch.Zone{{ID: "z", Name: "example.dev"}}}
	now := at("2026-10-06T21:00:00Z")
	poll := model.TS(now)
	sum := model.Summary{LastPollAt: &poll, Cycle: model.Cycle{Elapsed: 0.45}, Metrics: []model.Metric{
		{Key: "d1_rows_written", Label: "D1 rows written", Unit: "count", Used: 41e6, Included: 50e6, Pct: 0.82, ProjectedPct: 1.6, Projected: 80e6},
	}}
	ctx := context.Background()
	fired := m.evaluateAlerts(ctx, now, sum, hourlySeries{})
	ids := []string{}
	for _, a := range fired {
		ids = append(ids, a.ID)
	}
	if strings.Join(ids, " ") != "threshold:d1_rows_written:75:2026-09-23 projection:d1_rows_written:2026-10-06" {
		t.Fatalf("fired %v", ids)
	}
	// The biggest contributor's binding maps to the script's kill switches.
	sug := fired[0].Suggested
	if len(sug) == 0 || sug[0].Kind != model.KindWAFBlock || sug[0].Target != "api.example.dev" {
		t.Errorf("suggested %+v", sug)
	}
	if !strings.Contains(fired[0].Body, "maindb made 98%") {
		t.Errorf("body %q", fired[0].Body)
	}
	if again := m.evaluateAlerts(ctx, now.Add(time.Minute), sum, hourlySeries{}); len(again) != 0 {
		t.Errorf("fired again: %+v", again)
	}
	// Crossing the next level fires that level only.
	sum.Metrics[0].Pct = 0.95
	if next := m.evaluateAlerts(ctx, now.Add(2*time.Minute), sum, hourlySeries{}); len(next) != 1 || next[0].ID != "threshold:d1_rows_written:90:2026-09-23" {
		t.Errorf("next level: %+v", next)
	}
	// Muted metrics never alert.
	m.settings.Muted = []string{"d1_rows_written"}
	sum.Metrics[0].Pct = 1.1
	if muted := m.evaluateAlerts(ctx, now.Add(3*time.Minute), sum, hourlySeries{}); len(muted) != 0 {
		t.Errorf("muted fired: %+v", muted)
	}
	open, _ := st.Alerts(ctx, 10, true)
	if len(open) != 3 {
		t.Errorf("%d open alerts, want 3", len(open))
	}
	if _, err := st.AckAlert(ctx, open[0].ID, now); err != nil {
		t.Fatal(err)
	}
	if open, _ := st.Alerts(ctx, 10, true); len(open) != 2 {
		t.Errorf("after ack %d open", len(open))
	}
}

func TestMonitorAlertAfterTenMinutes(t *testing.T) {
	st, _ := store.Open(filepath.Join(t.TempDir(), "t.db"))
	defer st.Close()
	m := New(Config{Account: "a", DefaultCycleDay: 23}, nil, st, slog.New(slog.NewTextHandler(io.Discard, nil)), func(string, any) {})
	now := time.Now()
	m.started = now.Add(-time.Hour)
	m.pollErr, m.failingSince = errString("graphql: rate limited"), now.Add(-9*time.Minute)
	if got := m.evaluateAlerts(context.Background(), now, model.Summary{}, hourlySeries{}); len(got) != 0 {
		t.Errorf("fired after 9 min: %+v", got)
	}
	m.failingSince = now.Add(-11 * time.Minute)
	got := m.evaluateAlerts(context.Background(), now, model.Summary{}, hourlySeries{})
	if len(got) != 1 || got[0].Kind != "monitor" || !strings.Contains(got[0].Body, "rate limited") {
		t.Errorf("monitor alert: %+v", got)
	}
}

type errString string

func (e errString) Error() string { return string(e) }

func TestSpansAndDataAsOf(t *testing.T) {
	now := at("2026-10-06T21:37:00Z")
	s := newSpan("hour", now, 24)
	if s.start != at("2026-10-05T22:00:00Z") || s.index(at("2026-10-06T21:00:00Z")) != 23 || s.index(at("2026-10-05T21:00:00Z")) != -1 {
		t.Errorf("hour span %+v", s)
	}
	d := newSpan("day", now, 30)
	if w := zoneWindow(d); d.start != at("2026-09-07T00:00:00Z") || at(w["end"].(string)).Sub(d.start) > 30*24*time.Hour {
		t.Errorf("30 d span %+v window %v exceeds the zone dataset's 30 days", d, w)
	}
	fresh := map[string]time.Time{
		"workersInvocationsAdaptive":              at("2026-10-06T21:35:00Z"),
		"d1AnalyticsAdaptiveGroups":               at("2026-10-06T21:33:00Z"),
		"durableObjectsInvocationsAdaptiveGroups": at("2026-10-06T19:00:00Z"), // idle, not late
	}
	if got := dataAsOf(fresh, now); !got.Equal(at("2026-10-06T21:33:00Z")) {
		t.Errorf("dataAsOf = %v", got)
	}
}
