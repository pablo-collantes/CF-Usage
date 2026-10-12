package usage

import (
	"math"
	"testing"
	"time"

	"github.com/pablo-collantes/cfusage/server/internal/model"
)

func ts(s string) time.Time {
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		panic(err)
	}
	return t
}

func TestCycleBounds(t *testing.T) {
	cases := []struct {
		now        string
		day        int
		start, end string
		days       float64
	}{
		{"2026-10-06T21:39:00Z", 23, "2026-09-23T00:00:00Z", "2026-10-23T00:00:00Z", 30},
		{"2026-10-22T23:59:59Z", 23, "2026-09-23T00:00:00Z", "2026-10-23T00:00:00Z", 30},
		{"2026-10-23T00:00:00Z", 23, "2026-10-23T00:00:00Z", "2026-11-23T00:00:00Z", 31},
		{"2026-12-25T08:00:00Z", 23, "2026-12-23T00:00:00Z", "2027-01-23T00:00:00Z", 31}, // into next year
		{"2027-01-10T08:00:00Z", 23, "2026-12-23T00:00:00Z", "2027-01-23T00:00:00Z", 31}, // back into last year
		{"2027-02-28T12:00:00Z", 23, "2027-02-23T00:00:00Z", "2027-03-23T00:00:00Z", 28},
		{"2027-03-01T00:00:00Z", 23, "2027-02-23T00:00:00Z", "2027-03-23T00:00:00Z", 28},
		{"2026-10-06T21:39:00Z", 1, "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z", 31},
		{"2026-10-06T21:39:00-05:00", 23, "2026-09-23T00:00:00Z", "2026-10-23T00:00:00Z", 30}, // non-UTC input
	}
	for _, c := range cases {
		s, e := CycleBounds(ts(c.now), c.day)
		if !s.Equal(ts(c.start)) || !e.Equal(ts(c.end)) {
			t.Errorf("CycleBounds(%s, %d) = %s, %s; want %s, %s", c.now, c.day, s, e, c.start, c.end)
		}
		if d := e.Sub(s).Hours() / 24; d != c.days {
			t.Errorf("CycleBounds(%s): %v days, want %v", c.now, d, c.days)
		}
	}
}

func TestProjectFlow(t *testing.T) {
	start, end := ts("2026-09-23T00:00:00Z"), ts("2026-10-23T00:00:00Z") // 720 h

	// 12 h in: only the cycle average counts.
	if got := ProjectFlow(1200, nil, start, end, start.Add(12*time.Hour)); got != 1200+100*708 {
		t.Errorf("early projection = %v", got)
	}
	// 360 h in, 3600 used: 10/h average. Quiet recent days don't lower it...
	now := start.Add(360 * time.Hour)
	if got := ProjectFlow(3600, []float64{24, 24, 24}, start, end, now); got != 3600+10*360 {
		t.Errorf("quiet days projection = %v", got)
	}
	// ...two busy days in the last three raise it: median 2400/day = 100/h.
	if got := ProjectFlow(3600, []float64{240, 24, 2400, 2400}, start, end, now); got != 3600+100*360 {
		t.Errorf("busy days projection = %v", got)
	}
	// One burst day is not extrapolated: the median of (24, 24, 1e6) is 24/day,
	// below the 10/h average.
	if got := ProjectFlow(3600, []float64{24, 24, 1e6}, start, end, now); got != 3600+10*360 {
		t.Errorf("one-day burst projection = %v", got)
	}
	// Fewer than three complete days: the average alone.
	if got := ProjectFlow(3600, []float64{2400, 2400}, start, end, now); got != 3600+10*360 {
		t.Errorf("two-day projection = %v", got)
	}
	// At or after the end, nothing more is projected.
	if got := ProjectFlow(3600, []float64{2400, 2400, 2400}, start, end, end); got != 3600 {
		t.Errorf("end projection = %v", got)
	}
	if got := ProjectFlow(5, nil, start, end, start); got != 5 {
		t.Errorf("start projection = %v", got)
	}
}

func TestMedian(t *testing.T) {
	for _, c := range []struct {
		in   []float64
		want float64
	}{{nil, 0}, {[]float64{3}, 3}, {[]float64{5, 1, 3}, 3}, {[]float64{4, 1, 3, 2}, 2.5}} {
		if got := Median(c.in); got != c.want {
			t.Errorf("Median(%v) = %v, want %v", c.in, got, c.want)
		}
	}
}

func TestProjectStorage(t *testing.T) {
	start, end := ts("2026-09-23T00:00:00Z"), ts("2026-10-23T00:00:00Z")
	// 10 days averaging 2 GB, now 5 GB: (20 + 5*20) / 30.
	if got := ProjectStorage(20, 10, 5, start, end); math.Abs(got-4) > 1e-9 {
		t.Errorf("ProjectStorage = %v, want 4", got)
	}
	// More days seen than the cycle has (clock skew) never goes negative.
	if got := ProjectStorage(62, 31, 2, start, end); math.Abs(got-62.0/30) > 1e-9 {
		t.Errorf("ProjectStorage overflow = %v", got)
	}
}

func TestR2Class(t *testing.T) {
	cases := map[string]string{
		"PutObject": "r2_class_a", "ListObjects": "r2_class_a", "ListBuckets": "r2_class_a",
		"GetObject": "r2_class_b", "HeadObject": "r2_class_b", "HeadBucket": "r2_class_b",
		"DeleteObject": "", "DeleteObjects": "", "AbortMultipartUpload": "",
		// Not on the pricing page: classed by verb.
		"GetBucketSippyConfiguration": "r2_class_b", "GetBucketNotificationConfiguration": "r2_class_b",
		"PutBucketSomethingNew": "r2_class_a", "WeirdNewOp": "r2_class_a",
	}
	for op, want := range cases {
		if got := R2Class(op); got != want {
			t.Errorf("R2Class(%s) = %q, want %q", op, got, want)
		}
	}
}

func row(dims map[string]any, sum map[string]float64) Row { return Row{Dimensions: dims, Sum: sum} }

func TestExtractFlow(t *testing.T) {
	names := &Names{DO: map[string]string{"ns1": "mail:Mailbox"}, D1: map[string]string{"db1": "maindb"},
		KV: map[string]string{"abcd": "CACHE"}, Queues: map[string]string{"q1": "jobs"}}
	d := AccountData{
		"workers": {
			row(map[string]any{"scriptName": "api", "datetimeHour": "2026-10-06T20:00:00Z"}, map[string]float64{"requests": 10, "cpuTimeUs": 5000, "errors": 1, "subrequests": 3}),
			row(map[string]any{"scriptName": "api", "datetimeHour": "2026-10-06T21:00:00Z"}, map[string]float64{"requests": 5, "cpuTimeUs": 1000}),
		},
		"pages": {
			row(map[string]any{"scriptName": "api", "datetimeHour": "2026-10-06T20:00:00Z"}, map[string]float64{"requests": 10}), // also a Worker: skipped
			row(map[string]any{"scriptName": "site", "datetimeHour": "2026-10-06T20:00:00Z"}, map[string]float64{"requests": 7}),
		},
		"doInvocations": {row(map[string]any{"namespaceId": "ns1", "type": "http", "datetimeHour": "2026-10-06T20:00:00Z"}, map[string]float64{"requests": 4})},
		"doPeriodic": {row(map[string]any{"namespaceId": "ns1", "datetimeHour": "2026-10-06T20:00:00Z"},
			map[string]float64{"duration": 2.5, "rowsRead": 100, "rowsWritten": 10, "inboundWebsocketMsgCount": 40})},
		"d1": {row(map[string]any{"databaseId": "db1", "datetimeHour": "2026-10-06T20:00:00Z"}, map[string]float64{"rowsRead": 1000, "rowsWritten": 2})},
		"r2": {
			row(map[string]any{"bucketName": "b", "actionType": "PutObject", "datetimeHour": "2026-10-06T20:00:00Z"}, map[string]float64{"requests": 3}),
			row(map[string]any{"bucketName": "", "actionType": "ListBuckets", "datetimeHour": "2026-10-06T20:00:00Z"}, map[string]float64{"requests": 1}),
			row(map[string]any{"bucketName": "b", "actionType": "DeleteObject", "datetimeHour": "2026-10-06T20:00:00Z"}, map[string]float64{"requests": 99}),
		},
		"queues": {row(map[string]any{"queueId": "q1", "actionType": "WriteMessage", "datetimeHour": "2026-10-06T20:00:00Z"}, map[string]float64{"billableOperations": 6})},
		"kv": {
			row(map[string]any{"namespaceId": "ab-cd", "actionType": "read", "datetimeHour": "2026-10-06T20:00:00Z"}, map[string]float64{"requests": 8}),
			row(map[string]any{"namespaceId": "ab-cd", "actionType": "list", "datetimeHour": "2026-10-06T20:00:00Z"}, map[string]float64{"requests": 2}),
		},
	}
	got := map[[3]string]float64{}
	for _, p := range ExtractFlow(d, names, "datetimeHour") {
		got[[3]string{p.Metric, p.Resource, p.Bucket}] = p.Value
	}
	h20, h21 := "2026-10-06T20:00:00Z", "2026-10-06T21:00:00Z"
	want := map[[3]string]float64{
		{"workers_requests", "api", h20}: 10, {"workers_requests", "api", h21}: 5,
		{"workers_cpu_ms", "api", h20}: 5, {"workers_cpu_ms", "api", h21}: 1,
		{AuxErrors, "api", h20}: 1, {AuxSubrequests, "api", h20}: 3,
		{"workers_requests", "pages:site", h20}:  7,
		{"do_requests", "mail:Mailbox", h20}:     4 + 40.0/20, // WebSocket messages at 20:1
		{"do_duration_gbs", "mail:Mailbox", h20}: 2.5, {"do_sql_rows_read", "mail:Mailbox", h20}: 100,
		{"do_sql_rows_written", "mail:Mailbox", h20}: 10,
		{"d1_rows_read", "maindb", h20}:              1000, {"d1_rows_written", "maindb", h20}: 2,
		{"r2_class_a", "b", h20}: 3, {"r2_class_a", "(account)", h20}: 1,
		{"queues_ops", "jobs", h20}: 6,
		{"kv_reads", "CACHE", h20}:  8, {"kv_lists", "CACHE", h20}: 2,
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("%v = %v, want %v", k, got[k], v)
		}
	}
	for k := range got {
		if _, ok := want[k]; !ok {
			t.Errorf("unexpected point %v = %v", k, got[k])
		}
	}

	// Without a time dimension the buckets are empty and amounts add up.
	tot := 0.0
	for _, p := range ExtractFlow(d, names, "") {
		if p.Bucket != "" {
			t.Fatalf("total point has bucket %q", p.Bucket)
		}
		if p.Metric == "workers_requests" {
			tot += p.Value
		}
	}
	if tot != 22 {
		t.Errorf("workers_requests total = %v, want 22", tot)
	}
}

func TestExtractStorage(t *testing.T) {
	d := AccountData{
		"d1Storage": {
			{Dimensions: map[string]any{"databaseId": "a", "date": "2026-10-02"}, Max: map[string]float64{"databaseSizeBytes": 2e9}},
			{Dimensions: map[string]any{"databaseId": "a", "date": "2026-10-01"}, Max: map[string]float64{"databaseSizeBytes": 1e9}},
			{Dimensions: map[string]any{"databaseId": "b", "date": "2026-10-02"}, Max: map[string]float64{"databaseSizeBytes": 1e9}},
		},
		"r2Storage": {
			{Dimensions: map[string]any{"bucketName": "x", "storageClass": "Standard", "date": "2026-10-01"}, Max: map[string]float64{"payloadSize": 4e9, "metadataSize": 1e9}},
			{Dimensions: map[string]any{"bucketName": "x", "storageClass": "InfrequentAccess", "date": "2026-10-01"}, Max: map[string]float64{"payloadSize": 9e9}},
		},
	}
	st := ExtractStorage(d, &Names{D1: map[string]string{"a": "main"}})
	d1 := st["d1_storage_gb"]
	if d1.PerDay["2026-10-01"] != 1 || d1.PerDay["2026-10-02"] != 3 {
		t.Errorf("d1 per day = %v", d1.PerDay)
	}
	if d1.Latest["main"] != 2 || d1.Latest["b"] != 1 || d1.Current() != 3 {
		t.Errorf("d1 latest = %v", d1.Latest)
	}
	if r2 := st["r2_storage_gb"]; r2.Current() != 5 {
		t.Errorf("r2 only counts Standard storage: %v", r2.Latest)
	}
}

// The R2 case from 2026-10-06: a bucket grows from 0.1 GB to 4.8 GB on day 9 of a
// 30-day cycle. Used is the size now; the cycle bills the average of daily peaks.
func TestStorageCurrentAndBilledAverage(t *testing.T) {
	start := time.Date(2026, 9, 23, 0, 0, 0, 0, time.UTC)
	end := time.Date(2026, 10, 23, 0, 0, 0, 0, time.UTC)
	var rows []Row
	for i := 0; i < 15; i++ {
		size := 0.1e9
		if i >= 8 {
			size = 4.8e9
		}
		date := start.AddDate(0, 0, i).Format("2006-01-02")
		rows = append(rows, Row{Dimensions: map[string]any{"bucketName": "mail", "storageClass": "Standard", "date": date},
			Max: map[string]float64{"payloadSize": size}})
	}
	st := ExtractStorage(AccountData{"r2Storage": rows}, nil)["r2_storage_gb"]
	if math.Abs(st.Current()-4.8) > 1e-9 {
		t.Errorf("current = %v, want 4.8", st.Current())
	}
	if st.DaysSeen(start) != 15 {
		t.Errorf("days seen = %v", st.DaysSeen(start))
	}
	got := ProjectStorage(st.SumPeaks(start), st.DaysSeen(start), st.Current(), start, end)
	want := (8*0.1 + 7*4.8 + 15*4.8) / 30
	if math.Abs(got-want) > 1e-9 {
		t.Errorf("billed average = %v, want %v", got, want)
	}
}

func TestStorageGapsAndDeletions(t *testing.T) {
	row := func(id, date string, bytes float64) Row {
		return Row{Dimensions: map[string]any{"namespaceId": id, "date": date}, Max: map[string]float64{"byteCount": bytes}}
	}
	d := AccountData{"kvStorage": {
		row("a", "2026-10-01", 2e9), row("a", "2026-10-04", 3e9), // 10-02 and 10-03 missing
		row("gone", "2026-10-01", 1e9), row("gone", "2026-10-02", 1e9), // deleted after 10-02
		row("late", "2026-10-03", 1e9), // missing on the newest day only
		row("b", "2026-10-04", 0.5e9),
	}}
	st := ExtractStorage(d, nil)["kv_storage_gb"]
	want := map[string]float64{"2026-10-01": 3, "2026-10-02": 3, "2026-10-03": 3, "2026-10-04": 3.5}
	for day, v := range want {
		if math.Abs(st.PerDay[day]-v) > 1e-9 {
			t.Errorf("%s = %v, want %v (all: %v)", day, st.PerDay[day], v, st.PerDay)
		}
	}
	if _, ok := st.Latest["gone"]; ok {
		t.Errorf("a resource missing for two days counts as deleted: %v", st.Latest)
	}
	if math.Abs(st.Current()-4.5) > 1e-9 { // a 3 + late 1 + b 0.5
		t.Errorf("current = %v, want 4.5 (%v)", st.Current(), st.Latest)
	}
}

func TestDORequestsCountWebSocketMessagesAt20to1(t *testing.T) {
	d := AccountData{
		"doInvocations": {
			{Dimensions: map[string]any{"namespaceId": "mqtt", "type": "hibernation"}, Sum: map[string]float64{"requests": 2000}},
			{Dimensions: map[string]any{"namespaceId": "mqtt", "type": "http"}, Sum: map[string]float64{"requests": 10}},
			{Dimensions: map[string]any{"namespaceId": "chat", "type": "jsrpc"}, Sum: map[string]float64{"requests": 50}},
		},
		"doPeriodic": {
			{Dimensions: map[string]any{"namespaceId": "mqtt"}, Sum: map[string]float64{"inboundWebsocketMsgCount": 2000}},
			{Dimensions: map[string]any{"namespaceId": "chat"}, Sum: map[string]float64{"inboundWebsocketMsgCount": 400}},
		},
	}
	got := map[string]float64{}
	for _, p := range ExtractFlow(d, nil, "") {
		if p.Metric == "do_requests" {
			got[p.Resource] += p.Value
		}
	}
	// mqtt: 10 + 2000/20, its periodic count not added again; chat: 50 + 400/20.
	if got["mqtt"] != 110 || got["chat"] != 70 {
		t.Errorf("do_requests = %v", got)
	}
}

func TestStatus(t *testing.T) {
	s := model.DefaultSettings(23)
	cases := []struct {
		pct, proj, elapsed float64
		want               string
	}{
		{0.1, 0.2, 0.5, "ok"}, {0.5, 0.9, 0.5, "info"}, {0.75, 0.9, 0.5, "warn"},
		{0.2, 1.2, 0.5, "warn"}, {0.2, 1.2, 0.05, "ok"}, // projection ignored early in the cycle
		{0.9, 1, 0.5, "critical"}, {1, 1, 0.5, "over"},
	}
	for _, c := range cases {
		if got := Status(c.pct, c.proj, c.elapsed, s); got != c.want {
			t.Errorf("Status(%v, %v, %v) = %s, want %s", c.pct, c.proj, c.elapsed, got, c.want)
		}
	}
	s.ProjectionAlert = false
	if got := Status(0.2, 1.2, 0.5, s); got != "ok" {
		t.Errorf("projection off: %s", got)
	}
	if Worse("warn", "info") != "warn" || Worse("info", "over") != "over" {
		t.Error("Worse")
	}
}

func TestFmtAndDownsample(t *testing.T) {
	for in, want := range map[float64]string{1.23e9: "1.23B", 10e6: "10M", 4.5e6: "4.5M", 12345: "12.3k", 1234: "1,234", 0.5: "0.500", 12.25: "12.2"} {
		if got := Fmt(in); got != want {
			t.Errorf("Fmt(%v) = %s, want %s", in, got, want)
		}
	}
	v := make([]float64, 168)
	for i := range v {
		v[i] = 1
	}
	d := Downsample(v, 28)
	if len(d) != 28 || d[0] != 6 || total(d) != 168 {
		t.Errorf("Downsample(168, 28) = %v", d)
	}
	if d := Downsample([]float64{1, 2, 3}, 24); len(d) != 3 {
		t.Errorf("short series changed: %v", d)
	}
}

func total(v []float64) float64 {
	t := 0.0
	for _, x := range v {
		t += x
	}
	return t
}
