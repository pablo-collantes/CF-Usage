package usage

import (
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Row is one GraphQL group row. Only the numeric sub-objects the queries ask for
// are decoded.
type Row struct {
	Count      float64            `json:"count"`
	Dimensions map[string]any     `json:"dimensions"`
	Sum        map[string]float64 `json:"sum"`
	Max        map[string]float64 `json:"max"`
	Avg        map[string]float64 `json:"avg"`
	Ratio      map[string]float64 `json:"ratio"`
}

// Dim returns a dimension as a string (numbers are formatted without decimals).
func (r Row) Dim(k string) string {
	switch v := r.Dimensions[k].(type) {
	case string:
		return v
	case float64:
		return strconv.FormatFloat(v, 'f', -1, 64)
	case nil:
		return ""
	default:
		return fmt.Sprint(v)
	}
}

// AccountData is one account's result: dataset alias -> rows.
type AccountData map[string][]Row

// Names maps the IDs in analytics rows to the names people know them by.
type Names struct {
	D1     map[string]string // database uuid -> name
	Queues map[string]string // queue id -> name
	KV     map[string]string // namespace id without dashes -> title
	DO     map[string]string // namespace id -> "script:Class"
}

func (n *Names) lookup(m map[string]string, id string) string {
	if n != nil && m != nil {
		if v, ok := m[id]; ok {
			return v
		}
	}
	return id
}

func (n *Names) d1(id string) string    { return n.lookup(n.maps().D1, id) }
func (n *Names) queue(id string) string { return n.lookup(n.maps().Queues, id) }
func (n *Names) do(id string) string    { return n.lookup(n.maps().DO, id) }
func (n *Names) kv(id string) string {
	return n.lookup(n.maps().KV, strings.ReplaceAll(id, "-", ""))
}

func (n *Names) maps() *Names {
	if n == nil {
		return &Names{}
	}
	return n
}

// R2 operation classes from the R2 pricing page.
var (
	R2ClassA = set("ListBuckets", "PutBucket", "ListObjects", "PutObject", "CopyObject",
		"CompleteMultipartUpload", "CreateMultipartUpload", "LifecycleStorageTierTransition",
		"ListMultipartUploads", "UploadPart", "UploadPartCopy", "ListParts",
		"PutBucketEncryption", "PutBucketCors", "PutBucketLifecycleConfiguration")
	R2ClassB = set("HeadBucket", "HeadObject", "GetObject", "UsageSummary", "GetBucketEncryption",
		"GetBucketLocation", "GetBucketCors", "GetBucketLifecycleConfiguration")
	R2Free = set("DeleteObject", "DeleteBucket", "AbortMultipartUpload")
)

func set(xs ...string) map[string]bool {
	out := map[string]bool{}
	for _, x := range xs {
		out[x] = true
	}
	return out
}

// R2Class returns "r2_class_a", "r2_class_b" or "" (free). Operations not on the
// pricing page are classed by verb: deletes are free, reads go to Class B and
// anything else to Class A, the tighter allowance, so usage is never undercounted.
func R2Class(op string) string {
	switch {
	case R2ClassA[op]:
		return "r2_class_a"
	case R2ClassB[op]:
		return "r2_class_b"
	case R2Free[op], strings.HasPrefix(op, "Delete"):
		return ""
	case strings.HasPrefix(op, "Get"), strings.HasPrefix(op, "Head"):
		return "r2_class_b"
	}
	return "r2_class_a"
}

// Point is an amount of one metric used by one resource in one bucket (an hour
// "2026-10-06T21:00:00Z", or "" for a whole-range total).
type Point struct {
	Metric   string
	Resource string
	Bucket   string
	Value    float64
}

type pkey struct{ metric, resource, bucket string }

type acc map[pkey]float64

func (a acc) add(metric, resource, bucket string, v float64) {
	if v != 0 {
		a[pkey{metric, resource, bucket}] += v
	}
}

func (a acc) points() []Point {
	out := make([]Point, 0, len(a))
	for k, v := range a {
		out = append(out, Point{k.metric, k.resource, k.bucket, v})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Metric != out[j].Metric {
			return out[i].Metric < out[j].Metric
		}
		if out[i].Bucket != out[j].Bucket {
			return out[i].Bucket < out[j].Bucket
		}
		return out[i].Resource < out[j].Resource
	})
	return out
}

// IsWebSocketInvocation reports whether a Durable Objects invocation type is an
// incoming WebSocket message ("hibernation" for the Hibernation API).
func IsWebSocketInvocation(typ string) bool {
	t := strings.ToLower(typ)
	return t == "hibernation" || strings.Contains(t, "websocket")
}

// PagesPrefix marks Pages Functions projects among Workers resources.
const PagesPrefix = "pages:"

// ExtractFlow turns the flow datasets of an account query into points. bucketDim
// is the time dimension the query grouped by ("datetimeHour"), or "" for totals.
func ExtractFlow(d AccountData, names *Names, bucketDim string) []Point {
	a := acc{}
	b := func(r Row) string {
		if bucketDim == "" {
			return ""
		}
		return r.Dim(bucketDim)
	}

	workerScripts := map[string]bool{}
	for _, r := range d["workers"] {
		name := r.Dim("scriptName")
		workerScripts[name] = true
		a.add("workers_requests", name, b(r), r.Sum["requests"])
		a.add("workers_cpu_ms", name, b(r), r.Sum["cpuTimeUs"]/1000)
		a.add(AuxErrors, name, b(r), r.Sum["errors"])
		a.add(AuxSubrequests, name, b(r), r.Sum["subrequests"])
	}
	for _, r := range d["pages"] {
		name := r.Dim("scriptName")
		if workerScripts[name] {
			continue // already counted through workersInvocationsAdaptive
		}
		a.add("workers_requests", PagesPrefix+name, b(r), r.Sum["requests"])
		a.add(AuxErrors, PagesPrefix+name, b(r), r.Sum["errors"])
	}

	// Incoming WebSocket messages bill at 20:1 (DO pricing, example 4). A message to a
	// hibernated object is its own invocation of type "hibernation", so those rows
	// count a twentieth each. Namespaces with such rows skip the periodic
	// inboundWebsocketMsgCount below, so no message is counted twice.
	wsInvocations := map[string]bool{}
	for _, r := range d["doInvocations"] {
		v := r.Sum["requests"]
		if IsWebSocketInvocation(r.Dim("type")) {
			wsInvocations[r.Dim("namespaceId")] = true
			v /= 20
		}
		a.add("do_requests", names.do(r.Dim("namespaceId")), b(r), v)
	}
	for _, r := range d["doPeriodic"] {
		ns := names.do(r.Dim("namespaceId"))
		for _, f := range [][2]string{{"do_duration_gbs", "duration"}, {"do_sql_rows_read", "rowsRead"},
			{"do_sql_rows_written", "rowsWritten"}, {"do_kv_read_units", "storageReadUnits"},
			{"do_kv_write_units", "storageWriteUnits"}, {"do_kv_deletes", "storageDeletes"}} {
			a.add(f[0], ns, b(r), r.Sum[f[1]])
		}
		// Messages on non-hibernating WebSockets arrive inside an open invocation,
		// so they only show up here.
		if !wsInvocations[r.Dim("namespaceId")] {
			a.add("do_requests", ns, b(r), r.Sum["inboundWebsocketMsgCount"]/20)
		}
	}

	for _, r := range d["d1"] {
		db := names.d1(r.Dim("databaseId"))
		a.add("d1_rows_read", db, b(r), r.Sum["rowsRead"])
		a.add("d1_rows_written", db, b(r), r.Sum["rowsWritten"])
	}

	for _, r := range d["r2"] {
		if metric := R2Class(r.Dim("actionType")); metric != "" {
			bucket := r.Dim("bucketName")
			if bucket == "" {
				bucket = "(account)"
			}
			a.add(metric, bucket, b(r), r.Sum["requests"])
		}
	}

	for _, r := range d["queues"] {
		a.add("queues_ops", names.queue(r.Dim("queueId")), b(r), r.Sum["billableOperations"])
	}

	for _, r := range d["kv"] {
		metric := map[string]string{"read": "kv_reads", "write": "kv_writes", "delete": "kv_deletes", "list": "kv_lists"}[strings.ToLower(r.Dim("actionType"))]
		if metric != "" {
			a.add(metric, names.kv(r.Dim("namespaceId")), b(r), r.Sum["requests"])
		}
	}
	return a.points()
}

// Storage is one storage metric, in GB: per UTC day the sum of each resource's
// peak that day, and each current resource's latest size.
type Storage struct {
	PerDay   map[string]float64
	Latest   map[string]float64
	LastDate string // newest date any resource was reported, "" without data
}

// ExtractStorage collects the daily peaks the billed GB-month is computed from.
// Cloudflare's storage datasets skip days now and then (for one namespace, or a
// whole dataset), so a resource keeps its previous size through a gap. A resource
// last reported more than a day before the newest date is taken as deleted: it
// keeps its past days but leaves the current total.
func ExtractStorage(d AccountData, names *Names) map[string]Storage {
	out := map[string]Storage{}
	collect := func(metric string, rows []Row, resource func(Row) string, value func(Row) float64) {
		s := Storage{PerDay: map[string]float64{}, Latest: map[string]float64{}}
		byRes := map[string]map[string]float64{}
		for _, r := range rows {
			res, date := resource(r), r.Dim("date")
			if byRes[res] == nil {
				byRes[res] = map[string]float64{}
			}
			byRes[res][date] += value(r) / gb
			if date > s.LastDate {
				s.LastDate = date
			}
		}
		last, _ := time.Parse(dateFmt, s.LastDate)
		for res, days := range byRes {
			dates := make([]string, 0, len(days))
			for date := range days {
				dates = append(dates, date)
			}
			sort.Strings(dates)
			first, _ := time.Parse(dateFmt, dates[0])
			final, _ := time.Parse(dateFmt, dates[len(dates)-1])
			v := 0.0
			for t := first; !t.After(final); t = t.AddDate(0, 0, 1) {
				if x, ok := days[t.Format(dateFmt)]; ok {
					v = x
				}
				s.PerDay[t.Format(dateFmt)] += v
			}
			if !final.Before(last.AddDate(0, 0, -1)) {
				s.Latest[res] = v
			}
		}
		out[metric] = s
	}
	collect("d1_storage_gb", d["d1Storage"], func(r Row) string { return names.d1(r.Dim("databaseId")) },
		func(r Row) float64 { return r.Max["databaseSizeBytes"] })
	var std []Row
	for _, r := range d["r2Storage"] {
		// The free 10 GB-month covers Standard only; Infrequent Access bills from the first byte.
		if r.Dim("storageClass") == "Standard" {
			std = append(std, r)
		}
	}
	collect("r2_storage_gb", std, func(r Row) string { return r.Dim("bucketName") },
		func(r Row) float64 { return r.Max["payloadSize"] + r.Max["metadataSize"] })
	collect("do_sql_storage_gb", d["doSqlStorage"], func(r Row) string { return names.do(r.Dim("namespaceId")) },
		func(r Row) float64 { return r.Max["storedBytes"] })
	collect("do_kv_storage_gb", d["doKvStorage"], func(Row) string { return "all" },
		func(r Row) float64 { return r.Max["storedBytes"] })
	collect("kv_storage_gb", d["kvStorage"], func(r Row) string { return names.kv(r.Dim("namespaceId")) },
		func(r Row) float64 { return r.Max["byteCount"] })
	return out
}

const dateFmt = "2006-01-02"

// Current is the latest total size: what the app shows as used.
func (s Storage) Current() float64 {
	t := 0.0
	for _, v := range s.Latest {
		t += v
	}
	return t
}

// SumPeaks adds the daily peaks from start (a cycle start) on.
func (s Storage) SumPeaks(start time.Time) float64 {
	t := 0.0
	for d, v := range s.PerDay {
		if d >= start.Format(dateFmt) {
			t += v
		}
	}
	return t
}

// DaysSeen counts the days from start through the newest reported date.
func (s Storage) DaysSeen(start time.Time) int {
	last, err := time.Parse(dateFmt, s.LastDate)
	if err != nil || last.Before(start) {
		return 0
	}
	return int(last.Sub(start).Hours()/24) + 1
}
