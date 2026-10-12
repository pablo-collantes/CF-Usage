package monitor

import (
	"testing"

	"github.com/pablo-collantes/cfusage/server/internal/model"
)

func TestRoundForClients(t *testing.T) {
	s := model.Summary{
		Cycle:            model.Cycle{Elapsed: 0.4567891, DaysLeft: 16.33333},
		EstimatedBillUSD: 5.004999,
		Metrics: []model.Metric{{
			Used: 678170.7319999998, Pct: 0.023716, ProjectedPct: 0.05011201544406162,
			Projected: 501120.1544406162, Rate24h: 45294.706999999995,
			Overage: model.Overage{Units: 1234567.8, USD: 0.30000000000000004},
			Top:     []model.NameValue{{Name: "a", Value: 123156.00049}},
			Daily:   []model.DateValue{{Date: "2026-10-06", Value: 0.0004}},
		}},
	}
	roundForClients(&s)
	m := s.Metrics[0]
	for name, c := range map[string][2]float64{
		"used": {m.Used, 678170.732}, "pct": {m.Pct, 0.0237}, "projectedPct": {m.ProjectedPct, 0.0501},
		"projected": {m.Projected, 501100}, "rate24h": {m.Rate24h, 45290},
		"overage units": {m.Overage.Units, 1235000}, "overage usd": {m.Overage.USD, 0.3},
		"top": {m.Top[0].Value, 123156}, "daily": {m.Daily[0].Value, 0},
		"elapsed": {s.Cycle.Elapsed, 0.4568}, "daysLeft": {s.Cycle.DaysLeft, 16.33}, "bill": {s.EstimatedBillUSD, 5},
	} {
		if c[0] != c[1] {
			t.Errorf("%s: %v, want %v", name, c[0], c[1])
		}
	}
	if roundSig(0.000123456, 4) != 0.0001235 || roundSig(-98765, 2) != -99000 || roundSig(0, 4) != 0 {
		t.Errorf("roundSig: %v %v", roundSig(0.000123456, 4), roundSig(-98765, 2))
	}
}
