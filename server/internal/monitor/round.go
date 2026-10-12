package monitor

import (
	"math"

	"github.com/pablo-collantes/cfusage/server/internal/model"
)

// roundForClients trims the summary's numbers to the precision anything shows.
// Derived figures (percentages, projections, rates) otherwise change in their
// 15th digit on every poll as time moves on, which defeats the event stream's
// compression: rounded, an update costs about 460 bytes instead of 1,080. Raw
// amounts keep 3 decimals, which also drops float noise like 678170.7319999998.
// Alerts are judged before this, on the unrounded numbers.
func roundForClients(s *model.Summary) {
	s.Cycle.Elapsed = roundTo(s.Cycle.Elapsed, 4)
	s.Cycle.DaysLeft = roundTo(s.Cycle.DaysLeft, 2)
	s.EstimatedBillUSD = roundTo(s.EstimatedBillUSD, 2)
	for i := range s.Metrics {
		m := &s.Metrics[i]
		m.Used = roundTo(m.Used, 3)
		m.Pct = roundTo(m.Pct, 4)
		m.ProjectedPct = roundTo(m.ProjectedPct, 4)
		m.Projected = roundSig(m.Projected, 4)
		m.Rate24h = roundSig(m.Rate24h, 4)
		m.Overage.Units = roundSig(m.Overage.Units, 4)
		m.Overage.USD = roundTo(m.Overage.USD, 2)
		for j := range m.Top {
			m.Top[j].Value = roundTo(m.Top[j].Value, 3)
		}
		for j := range m.Daily {
			m.Daily[j].Value = roundTo(m.Daily[j].Value, 3)
		}
	}
}

func roundTo(x float64, decimals int) float64 {
	p := math.Pow10(decimals)
	return math.Round(x*p) / p
}

// roundSig rounds to n significant digits.
func roundSig(x float64, n int) float64 {
	if x == 0 || math.IsNaN(x) || math.IsInf(x, 0) {
		return x
	}
	return roundTo(x, n-1-int(math.Floor(math.Log10(math.Abs(x)))))
}
