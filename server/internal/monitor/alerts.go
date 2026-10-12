package monitor

import (
	"context"
	"fmt"
	"math"
	"sort"
	"time"

	"github.com/pablo-collantes/cfusage/server/internal/model"
	"github.com/pablo-collantes/cfusage/server/internal/usage"
)

// An alert's id encodes what it is about, and the store keeps each id once, so
// every alert fires exactly once:
//
//	threshold:<metric>:<percent>:<cycle start>   highest level crossed this cycle
//	projection:<metric>:<day>                    at most daily, after 10% of the cycle
//	spike:<metric>:<hour>                        an hour far above the 24 h before it
//	monitor:polls:<first failure>                polls failing for 10 minutes

// evaluateAlerts returns the alerts that fired for the first time.
func (m *Monitor) evaluateAlerts(ctx context.Context, now time.Time, sum model.Summary, h hourlySeries) []model.Alert {
	m.mu.RLock()
	s := m.settings
	totals, storage := m.totals, m.storage
	pollErr, failingSince, started := m.pollErr, m.failingSince, m.started
	m.mu.RUnlock()
	muted := map[string]bool{}
	for _, k := range s.Muted {
		muted[k] = true
	}
	start, end := usage.CycleBounds(now, s.CycleDay)
	inv := m.inventory()
	open := m.openKillIDs(ctx)

	var cands []model.Alert
	add := func(a model.Alert) { cands = append(cands, a) }

	if pollErr != nil && !failingSince.IsZero() && now.Sub(failingSince) >= 10*time.Minute && now.Sub(started) >= 10*time.Minute {
		add(model.Alert{
			ID: "monitor:polls:" + iso(failingSince), Kind: "monitor", Severity: "critical",
			Title: "Usage monitor can't read Cloudflare",
			Body:  fmt.Sprintf("No successful poll for %.0f min, so usage alerts are paused. Last error: %v", now.Sub(failingSince).Minutes(), pollErr),
		})
	}

	// Only judge usage on data we actually have.
	if sum.LastPollAt != nil {
		for _, mt := range sum.Metrics {
			if muted[mt.Key] {
				continue
			}
			def, _ := usage.Lookup(mt.Key)
			contributors := totals[mt.Key]
			if def.Kind == "storage" {
				contributors = storage[mt.Key].Latest
			}
			top := ranked(contributors)
			suggested := m.suggest(inv, mt.Key, top, open)
			topLine := topShare(top, contributors)

			if a, ok := thresholdAlert(mt, s, start, end, now, topLine); ok {
				a.Suggested = suggested
				add(a)
			}
			if s.ProjectionAlert && sum.Cycle.Elapsed >= 0.1 && mt.ProjectedPct >= 1 && mt.Pct < 1 {
				body := fmt.Sprintf("Projected %s of %s by %s (%.0f%%), about $%.2f over.%s",
					usage.FmtUnit(mt.Projected, mt.Unit), usage.FmtUnit(mt.Included, mt.Unit), end.Format("Jan 2"),
					mt.ProjectedPct*100, mt.Overage.USD, topLine)
				if def.Kind == "storage" {
					body = fmt.Sprintf("This cycle is on track to bill an average of %s-month against %s included (%.0f%%), about $%.2f over.%s",
						usage.FmtUnit(mt.Projected, mt.Unit), usage.FmtUnit(mt.Included, mt.Unit),
						mt.ProjectedPct*100, mt.Overage.USD, topLine)
				}
				add(model.Alert{
					ID: "projection:" + mt.Key + ":" + day(now), Kind: "projection", Severity: "warn", Metric: model.Str(mt.Key),
					Title: mt.Label + " on track to pass the allowance",
					Body:  body, Suggested: suggested,
				})
			}
			if def.Kind == "flow" {
				// The hour in progress too, so a burst alerts within minutes rather than
				// after its hour ends. Alert IDs are per hour, so each fires once.
				for _, hour := range []time.Time{truncHour(now).Add(-time.Hour), truncHour(now)} {
					if a, ok := spikeAlert(mt, def, s, h, hour, now, end); ok {
						hourTop := ranked(h.byRes[mt.Key][hour])
						a.Body += topShare(hourTop, h.byRes[mt.Key][hour])
						a.Suggested = m.suggest(inv, mt.Key, hourTop, open)
						add(a)
					}
				}
			}
		}
	}

	var fired []model.Alert
	for _, a := range cands {
		a.CreatedAt = model.TS(now)
		if a.Suggested == nil {
			a.Suggested = []model.KillTarget{}
		}
		ok, err := m.store.InsertAlert(ctx, a)
		if err != nil {
			m.log.Warn("saving alert", "id", a.ID, "err", err)
			continue
		}
		if ok {
			fired = append(fired, a)
		}
	}
	return fired
}

// thresholdAlert is the highest level crossed. Lower levels crossed at the same
// time aren't sent separately.
func thresholdAlert(mt model.Metric, s model.Settings, start, end, now time.Time, topLine string) (model.Alert, bool) {
	levels := []struct {
		at       float64
		severity string
	}{{1, "critical"}, {s.Thresholds.Critical, "critical"}, {s.Thresholds.Warn, "warn"}, {s.Thresholds.Info, "info"}}
	for _, l := range levels {
		if mt.Pct < l.at {
			continue
		}
		pct := int(math.Round(l.at * 100))
		title := fmt.Sprintf("%s at %d%%", mt.Label, pct)
		if l.at >= 1 {
			title = mt.Label + " is over the included allowance"
		}
		return model.Alert{
			ID: fmt.Sprintf("threshold:%s:%d:%s", mt.Key, pct, day(start)), Kind: "threshold", Severity: l.severity,
			Metric: model.Str(mt.Key), Title: title,
			Body: fmt.Sprintf("%s of %s used, %.0f days left.%s", usage.FmtUnit(mt.Used, mt.Unit),
				usage.FmtUnit(mt.Included, mt.Unit), math.Ceil(end.Sub(now).Hours()/24-0.05), topLine),
		}, true
	}
	return model.Alert{}, false
}

// spikeAlert compares one hour (finished, or in progress and judged on what it
// has so far) with the average hour of the 24 before. It is critical when that
// rate would use up the allowance before the cycle ends.
func spikeAlert(mt model.Metric, def usage.Def, s model.Settings, h hourlySeries, hour, now, end time.Time) (model.Alert, bool) {
	last := h.total[mt.Key][hour]
	mean := h.sum(mt.Key, hour.Add(-24*time.Hour), hour) / 24
	if last <= 0 || last < s.SpikeFactor*mean || last < s.SpikeMinShare*def.Included {
		return model.Alert{}, false
	}
	left := end.Sub(now).Hours()
	severity := "warn"
	if mt.Used+last*left >= def.Included {
		severity = "critical"
	}
	ratio := "no traffic in the 24 h before"
	if mean > 0 {
		ratio = fmt.Sprintf("%.1f× the 24 h average", last/mean)
	}
	so := ""
	if now.Before(hour.Add(time.Hour)) {
		so = " so far"
	}
	body := fmt.Sprintf("%s%s in the hour from %s UTC, %s.", usage.FmtUnit(last, mt.Unit), so, hour.Format("15:04"), ratio)
	if remaining := def.Included - mt.Used; remaining > 0 && severity == "critical" {
		body += fmt.Sprintf(" At this rate the allowance runs out in %.0f h.", remaining/last)
	}
	return model.Alert{
		ID: "spike:" + mt.Key + ":" + hour.Format("2006-01-02T15"), Kind: "spike", Severity: severity,
		Metric: model.Str(mt.Key), Title: "Spike in " + mt.Label, Body: body,
	}, true
}

// ranked returns resources by amount, largest first.
func ranked(by map[string]float64) []string {
	out := make([]string, 0, len(by))
	for k, v := range by {
		if v > 0 {
			out = append(out, k)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if by[out[i]] != by[out[j]] {
			return by[out[i]] > by[out[j]]
		}
		return out[i] < out[j]
	})
	return out
}

func topShare(top []string, by map[string]float64) string {
	if len(top) == 0 {
		return ""
	}
	t := 0.0
	for _, v := range by {
		t += v
	}
	if t <= 0 {
		return ""
	}
	return fmt.Sprintf(" %s made %.0f%%.", top[0], by[top[0]]/t*100)
}

// suggest maps the biggest contributors to their kill switches, biggest first.
func (m *Monitor) suggest(inv *Inventory, metric string, top []string, open map[string]bool) []model.KillTarget {
	var out []model.KillTarget
	for i, r := range top {
		if i == 3 {
			break
		}
		out = append(out, inv.resourceKills(metric, r, open)...)
	}
	return dedupe(out, 6)
}
