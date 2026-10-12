// Package usage turns GraphQL Analytics rows into billable usage: metric
// definitions and allowances, billing-cycle maths, queries and row extraction.
// Everything here is pure, so it is what the unit tests cover.
package usage

// Def is one billable metric with its Workers Paid allowance and overage price.
// Sources: developers.cloudflare.com/{workers,r2,d1,durable-objects,queues,kv}/platform/pricing
type Def struct {
	Key      string
	Label    string
	Group    string
	Unit     string // count | ms | gbs | gb
	Kind     string // flow | storage
	Included float64
	PerUnit  float64 // USD per unit above Included
	Price    string
	Sampled  bool // from an adaptive (sampled) event dataset, so counts are estimates
}

const (
	m  = 1e6
	gb = 1e9 // decimal GB; slightly overstates usage if Cloudflare bills GiB, which errs safe
)

// Defs is the fixed order the API and the app present metrics in.
var Defs = []Def{
	{"workers_requests", "Workers requests", "Workers", "count", "flow", 10 * m, 0.30 / m, "$0.30 / million", true},
	{"workers_cpu_ms", "Workers CPU time", "Workers", "ms", "flow", 30 * m, 0.02 / m, "$0.02 / million ms", true},
	{"do_requests", "DO requests", "Durable Objects", "count", "flow", 1 * m, 0.15 / m, "$0.15 / million", true},
	{"do_duration_gbs", "DO duration", "Durable Objects", "gbs", "flow", 400_000, 12.50 / m, "$12.50 / million GB-s", false},
	{"do_sql_rows_read", "DO SQLite rows read", "Durable Objects", "count", "flow", 25_000 * m, 0.001 / m, "$0.001 / million", false},
	{"do_sql_rows_written", "DO SQLite rows written", "Durable Objects", "count", "flow", 50 * m, 1.00 / m, "$1.00 / million", false},
	{"do_sql_storage_gb", "DO SQLite storage", "Durable Objects", "gb", "storage", 5, 0.20, "$0.20 / GB-month", false},
	{"do_kv_read_units", "DO KV read units", "Durable Objects", "count", "flow", 1 * m, 0.20 / m, "$0.20 / million", false},
	{"do_kv_write_units", "DO KV write units", "Durable Objects", "count", "flow", 1 * m, 1.00 / m, "$1.00 / million", false},
	{"do_kv_deletes", "DO KV deletes", "Durable Objects", "count", "flow", 1 * m, 1.00 / m, "$1.00 / million", false},
	{"do_kv_storage_gb", "DO KV storage", "Durable Objects", "gb", "storage", 1, 0.20, "$0.20 / GB-month", false},
	{"d1_rows_read", "D1 rows read", "D1", "count", "flow", 25_000 * m, 0.001 / m, "$0.001 / million", true},
	{"d1_rows_written", "D1 rows written", "D1", "count", "flow", 50 * m, 1.00 / m, "$1.00 / million", true},
	{"d1_storage_gb", "D1 storage", "D1", "gb", "storage", 5, 0.75, "$0.75 / GB-month", false},
	{"r2_class_a", "R2 Class A ops", "R2", "count", "flow", 1 * m, 4.50 / m, "$4.50 / million", true},
	{"r2_class_b", "R2 Class B ops", "R2", "count", "flow", 10 * m, 0.36 / m, "$0.36 / million", true},
	{"r2_storage_gb", "R2 storage", "R2", "gb", "storage", 10, 0.015, "$0.015 / GB-month", false},
	{"kv_reads", "KV reads", "KV", "count", "flow", 10 * m, 0.50 / m, "$0.50 / million", true},
	{"kv_writes", "KV writes", "KV", "count", "flow", 1 * m, 5.00 / m, "$5.00 / million", true},
	{"kv_deletes", "KV deletes", "KV", "count", "flow", 1 * m, 5.00 / m, "$5.00 / million", true},
	{"kv_lists", "KV lists", "KV", "count", "flow", 1 * m, 5.00 / m, "$5.00 / million", true},
	{"kv_storage_gb", "KV storage", "KV", "gb", "storage", 1, 0.50, "$0.50 / GB-month", false},
	{"queues_ops", "Queues operations", "Queues", "count", "flow", 1 * m, 0.40 / m, "$0.40 / million", true},
}

// Auxiliary, non-billable series stored next to the metrics for the Workers views.
const (
	AuxErrors      = "x_errors"
	AuxSubrequests = "x_subrequests"
)

// BaseUSD is the Workers Paid subscription.
const BaseUSD = 5.0

var byKey = func() map[string]Def {
	out := map[string]Def{}
	for _, d := range Defs {
		out[d.Key] = d
	}
	return out
}()

func Lookup(key string) (Def, bool) {
	d, ok := byKey[key]
	return d, ok
}

// Resource kinds a metric's contributors belong to, for mapping them to kill switches.
func ResourceKind(key string) string {
	switch key {
	case "workers_requests", "workers_cpu_ms", AuxErrors, AuxSubrequests:
		return "script"
	case "queues_ops":
		return "queue"
	}
	switch Lookup2(key).Group {
	case "Durable Objects":
		return "do"
	case "D1":
		return "d1"
	case "R2":
		return "r2"
	case "KV":
		return "kv"
	}
	return ""
}

// Lookup2 is Lookup without the ok flag, for callers that tolerate a zero Def.
func Lookup2(key string) Def { return byKey[key] }
