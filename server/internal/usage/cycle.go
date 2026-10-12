package usage

import (
	"fmt"
	"math"
	"sort"
	"time"

	"github.com/pablo-collantes/cfusage/server/internal/model"
)

// CycleBounds returns the billing cycle containing now. It starts on day `day`
// (1-28) at 00:00 UTC and ends on the same day of the next month.
func CycleBounds(now time.Time, day int) (start, end time.Time) {
	now = now.UTC()
	start = time.Date(now.Year(), now.Month(), day, 0, 0, 0, 0, time.UTC)
	if now.Before(start) {
		start = start.AddDate(0, -1, 0) // day <= 28, so AddDate never normalises into another month
	}
	return start, start.AddDate(0, 1, 0)
}

// ProjectFlow estimates a flow metric's end-of-cycle total: used plus, for the
// hours left, the larger of the cycle-average rate and the median of the last
// three complete days. The median keeps a one-day burst (a backfill, an import)
// from being extrapolated over the rest of the cycle, while a change that lasts
// two days or more still lifts the projection. Sudden jumps are the hourly spike
// alert's job. With fewer than three complete days only the average is used.
func ProjectFlow(used float64, recentDays []float64, start, end, now time.Time) float64 {
	elapsed := now.Sub(start).Hours()
	left := end.Sub(now).Hours()
	if elapsed <= 0 || left <= 0 {
		return used
	}
	rate := used / elapsed
	if len(recentDays) >= 3 {
		rate = math.Max(rate, Median(recentDays[len(recentDays)-3:])/24)
	}
	return used + rate*left
}

// Median of xs (0 for none). xs is not modified.
func Median(xs []float64) float64 {
	if len(xs) == 0 {
		return 0
	}
	s := append([]float64(nil), xs...)
	sort.Float64s(s)
	n := len(s)
	if n%2 == 1 {
		return s[n/2]
	}
	return (s[n/2-1] + s[n/2]) / 2
}

// ProjectStorage estimates the GB-months the cycle will bill. Cloudflare bills
// storage as the average of each day's peak over the billing period (R2 pricing),
// so: the daily peaks of the days seen so far, plus the latest size for every
// remaining day, over the days in the cycle. Early in a cycle this can sit below
// the current size, when storage grew recently.
func ProjectStorage(sumDailyPeaks float64, daysWithData int, latest float64, start, end time.Time) float64 {
	days := math.Round(end.Sub(start).Hours() / 24)
	if days <= 0 {
		return latest
	}
	rest := math.Max(0, days-float64(daysWithData))
	return (sumDailyPeaks + latest*rest) / days
}

// Status orders: ok < info < warn < critical < over.
var statusRank = map[string]int{"ok": 0, "info": 1, "warn": 2, "critical": 3, "over": 4}

func Worse(a, b string) string {
	if statusRank[b] > statusRank[a] {
		return b
	}
	return a
}

// Status grades a metric. A projection only counts once 10% of the cycle has
// passed: earlier than that a few busy hours extrapolate to nonsense.
func Status(pct, projectedPct, elapsed float64, s model.Settings) string {
	switch {
	case pct >= 1:
		return "over"
	case pct >= s.Thresholds.Critical:
		return "critical"
	case pct >= s.Thresholds.Warn || (s.ProjectionAlert && elapsed >= 0.1 && projectedPct >= 1):
		return "warn"
	case pct >= s.Thresholds.Info:
		return "info"
	}
	return "ok"
}

// Fmt is the compact number format used in alerts: 1.23B, 4.56M, 78.9k, 1,234, 0.123.
func Fmt(n float64) string {
	a := math.Abs(n)
	switch {
	case a >= 1e9:
		return trim(fmt.Sprintf("%.2f", n/1e9)) + "B"
	case a >= 1e6:
		return trim(fmt.Sprintf("%.2f", n/1e6)) + "M"
	case a >= 1e4:
		return trim(fmt.Sprintf("%.1f", n/1e3)) + "k"
	case n == math.Trunc(n):
		return commas(int64(n))
	case a >= 10:
		return fmt.Sprintf("%.1f", n)
	}
	return fmt.Sprintf("%.3f", n)
}

func trim(s string) string {
	for len(s) > 1 && s[len(s)-1] == '0' {
		s = s[:len(s)-1]
	}
	if s[len(s)-1] == '.' {
		s = s[:len(s)-1]
	}
	return s
}

func commas(n int64) string {
	s := fmt.Sprintf("%d", n)
	neg := n < 0
	if neg {
		s = s[1:]
	}
	out := ""
	for i, c := range s {
		if i > 0 && (len(s)-i)%3 == 0 {
			out += ","
		}
		out += string(c)
	}
	if neg {
		out = "-" + out
	}
	return out
}

// FmtUnit formats an amount in a metric's unit, e.g. "9M", "2.4 GB", "120k GB-s".
func FmtUnit(v float64, unit string) string {
	switch unit {
	case "gb":
		return fmt.Sprintf("%.2f GB", v)
	case "gbs":
		return Fmt(v) + " GB-s"
	case "ms":
		return Fmt(v) + " ms"
	}
	return Fmt(v)
}

// Downsample sums adjacent points into at most n groups.
func Downsample(v []float64, n int) []float64 {
	if n <= 0 || len(v) <= n {
		return append([]float64{}, v...)
	}
	g := (len(v) + n - 1) / n
	out := make([]float64, 0, n)
	for i := 0; i < len(v); i += g {
		s := 0.0
		for j := i; j < i+g && j < len(v); j++ {
			s += v[j]
		}
		out = append(out, s)
	}
	return out
}
