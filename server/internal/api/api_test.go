package api

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/pablo-collantes/cfusage/server/internal/killswitch"
	"github.com/pablo-collantes/cfusage/server/internal/monitor"
	"github.com/pablo-collantes/cfusage/server/internal/store"
)

func TestWriteEventFraming(t *testing.T) {
	var b bytes.Buffer
	if err := WriteEvent(&b, "summary", []byte(`{"a":1}`)); err != nil {
		t.Fatal(err)
	}
	if got := b.String(); got != "event: summary\ndata: {\"a\":1}\n\n" {
		t.Errorf("single line: %q", got)
	}
	b.Reset()
	_ = WriteEvent(&b, "x", []byte("one\ntwo\r\nthree"))
	if got := b.String(); got != "event: x\ndata: one\ndata: two\ndata: three\n\n" {
		t.Errorf("multi line: %q", got)
	}
	b.Reset()
	_ = WriteComment(&b, "ping")
	if b.String() != ": ping\n\n" {
		t.Errorf("comment: %q", b.String())
	}
}

func newAPI(t *testing.T) *API {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	hub := NewHub()
	mon := monitor.New(monitor.Config{Account: "a", DefaultCycleDay: 23}, nil, st, log, hub.Publish)
	kill := &killswitch.Service{Store: st, DryRun: true, Emit: mon.OnKill, Log: log}
	mon.Kill = kill
	return &API{Mon: mon, Kill: kill, Store: st, Hub: hub, Log: log, AllowedLogins: []string{"me@example.com"},
		PingEvery: 40 * time.Millisecond, StreamFor: 400 * time.Millisecond}
}

func TestEventStream(t *testing.T) {
	a := newAPI(t)
	srv := httptest.NewServer(a.Handler())
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/api/v1/events")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if ct := resp.Header.Get("Content-Type"); ct != "text/event-stream" {
		t.Errorf("content type %q", ct)
	}
	sc := bufio.NewScanner(resp.Body)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	var lines []string
	published := false
	t0 := time.Now()
	for sc.Scan() {
		lines = append(lines, sc.Text())
		if !published && sc.Text() == "" { // the first event (summary) is complete
			published = true
			for a.Hub.Count() == 0 {
				time.Sleep(time.Millisecond)
			}
			a.Hub.Publish("alert", map[string]string{"id": "spike:x"})
		}
	}
	took := time.Since(t0)
	all := strings.Join(lines, "\n")
	if !strings.HasPrefix(all, "event: summary\ndata: {") {
		t.Errorf("stream doesn't start with a summary: %.80q", all)
	}
	if !strings.Contains(all, "event: alert\ndata: {\"id\":\"spike:x\"}\n") {
		t.Errorf("alert event missing: %q", all)
	}
	if !strings.Contains(all, ": ping") {
		t.Errorf("no keep-alive ping: %q", all)
	}
	if took > 2*time.Second {
		t.Errorf("stream didn't end after StreamFor (%v)", took)
	}
}

func TestMutatingNeedsALogin(t *testing.T) {
	a := newAPI(t)
	h := a.Handler()
	do := func(remote string, hdr map[string]string) int {
		r := httptest.NewRequest("PUT", "/api/v1/settings", strings.NewReader(`{"cycleDay":0}`))
		r.RemoteAddr = remote
		for k, v := range hdr {
			r.Header.Set(k, v)
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w.Code
	}
	// Allowed callers get through to validation (400 for cycleDay 0); others are refused.
	cases := []struct {
		name   string
		remote string
		hdr    map[string]string
		want   int
	}{
		{"allowed login via serve", "127.0.0.1:5000", map[string]string{"Tailscale-User-Login": "me@example.com", "X-Forwarded-For": "100.1.2.3"}, 400},
		{"other login", "127.0.0.1:5000", map[string]string{"Tailscale-User-Login": "eve@example.com", "X-Forwarded-For": "100.1.2.3"}, 403},
		{"proxied without login (tagged device)", "127.0.0.1:5000", map[string]string{"X-Forwarded-For": "100.1.2.3"}, 403},
		{"local test, no headers", "127.0.0.1:5000", nil, 400},
		{"not loopback", "100.1.2.3:5000", nil, 403},
	}
	for _, c := range cases {
		if got := do(c.remote, c.hdr); got != c.want {
			t.Errorf("%s: %d, want %d", c.name, got, c.want)
		}
	}
}

func TestReadEndpointsBeforeFirstPoll(t *testing.T) {
	a := newAPI(t)
	h := a.Handler()
	for path, want := range map[string]int{
		"/api/v1/health": 200, "/api/v1/summary": 200, "/api/v1/settings": 200, "/api/v1/alerts": 200,
		"/api/v1/kill": 200, "/api/v1/metrics/nope": 404, "/api/v1/workers?range=2d": 400, "/api/v1/nope": 404,
	} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
		if w.Code != want {
			t.Errorf("GET %s: %d, want %d: %s", path, w.Code, want, w.Body)
		}
		if !strings.HasPrefix(w.Header().Get("Content-Type"), "application/json") {
			t.Errorf("GET %s: content type %q", path, w.Header().Get("Content-Type"))
		}
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/summary", nil))
	for _, k := range []string{`"metrics":[]`, `"alerts":[]`, `"kills":[]`, `"pollError":"no poll has completed yet"`} {
		if !strings.Contains(w.Body.String(), k) {
			t.Errorf("empty summary lacks %s: %s", k, w.Body)
		}
	}
}

// readEvents parses an SSE body into events ("name data" or ": comment"), each
// sent on the channel as soon as it is complete.
func readEvents(body io.Reader) <-chan string {
	out := make(chan string, 64)
	go func() {
		defer close(out)
		sc := bufio.NewScanner(body)
		sc.Buffer(make([]byte, 1<<20), 1<<20)
		var cur []string
		for sc.Scan() {
			line := sc.Text()
			if line != "" {
				cur = append(cur, strings.TrimPrefix(strings.TrimPrefix(line, "event: "), "data: "))
				continue
			}
			out <- strings.Join(cur, " ")
			cur = nil
		}
	}()
	return out
}

func next(t *testing.T, evs <-chan string, within time.Duration) string {
	t.Helper()
	select {
	case e := <-evs:
		return e
	case <-time.After(within):
		t.Fatalf("no event within %v", within)
		return ""
	}
}

func TestEventStreamHoldsSummariesUntilTheIntervalEnds(t *testing.T) {
	a := newAPI(t)
	a.PingEvery, a.StreamFor, a.MinEvery = time.Hour, 5*time.Second, time.Millisecond
	srv := httptest.NewServer(a.Handler())
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/api/v1/events?every=0.4")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	evs := readEvents(resp.Body)
	if e := next(t, evs, time.Second); !strings.HasPrefix(e, "summary {") {
		t.Fatalf("first event %.40q", e)
	}
	for a.Hub.Count() == 0 {
		time.Sleep(time.Millisecond)
	}
	// The summary sent on connect doesn't start the interval: the first poll's goes out.
	t0 := time.Now()
	a.Hub.Publish("summary", map[string]int{"n": 1})
	if e := next(t, evs, time.Second); e != `summary {"n":1}` || time.Since(t0) > 100*time.Millisecond {
		t.Fatalf("first poll after connect: %q after %v", e, time.Since(t0))
	}
	// Inside the interval summaries are held, and only the newest is kept. With no
	// newer one, it goes out at the end of the interval plus the tolerance (0.6 s).
	a.Hub.Publish("summary", map[string]int{"n": 2})
	a.Hub.Publish("summary", map[string]int{"n": 3})
	if e := next(t, evs, 2*time.Second); e != `summary {"n":3}` {
		t.Errorf("held summary: got %q, want only the newest", e)
	}
	if took := time.Since(t0); took < 550*time.Millisecond {
		t.Errorf("held summary went out after %v, before the interval and tolerance", took)
	}

	// Once the interval (less the tolerance) has passed, the next one goes out at once.
	time.Sleep(250 * time.Millisecond)
	t1 := time.Now()
	a.Hub.Publish("summary", map[string]int{"n": 4})
	if e := next(t, evs, time.Second); e != `summary {"n":4}` || time.Since(t1) > 100*time.Millisecond {
		t.Errorf("after the interval: %q after %v", e, time.Since(t1))
	}

	// An alert goes out at once, and so does the summary that follows it (the
	// monitor publishes the alert's poll right after it). The held one is dropped.
	a.Hub.Publish("summary", map[string]int{"n": 5})
	t2 := time.Now()
	a.Hub.Publish("alert", map[string]string{"id": "spike:x"})
	a.Hub.Publish("summary", map[string]int{"n": 6})
	if e := next(t, evs, time.Second); e != `alert {"id":"spike:x"}` {
		t.Errorf("alert: %q", e)
	}
	if e := next(t, evs, time.Second); e != `summary {"n":6}` {
		t.Errorf("after the alert: %q", e)
	}
	if took := time.Since(t2); took > 100*time.Millisecond {
		t.Errorf("alert and its summary took %v", took)
	}
	// Nothing else: the held summary (n=5) was replaced.
	select {
	case e := <-evs:
		if e != "" {
			t.Errorf("unexpected event %q", e)
		}
	case <-time.After(800 * time.Millisecond):
	}
}

func TestEventStreamGzip(t *testing.T) {
	a := newAPI(t)
	a.PingEvery = time.Hour
	srv := httptest.NewServer(a.Handler())
	defer srv.Close()
	req, _ := http.NewRequest("GET", srv.URL+"/api/v1/events", nil)
	req.Header.Set("Accept-Encoding", "gzip") // set by hand, so Go doesn't decompress for us
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if ce := resp.Header.Get("Content-Encoding"); ce != "gzip" {
		t.Fatalf("Content-Encoding %q", ce)
	}
	zr, err := gzip.NewReader(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	evs := readEvents(zr)
	// Each event must be flushed through the compressor, not wait for the stream to end.
	if e := next(t, evs, time.Second); !strings.HasPrefix(e, "summary {") {
		t.Fatalf("first event %.40q", e)
	}
	for a.Hub.Count() == 0 {
		time.Sleep(time.Millisecond)
	}
	a.Hub.Publish("alert", map[string]string{"id": "spike:x"})
	if e := next(t, evs, time.Second); e != `alert {"id":"spike:x"}` {
		t.Errorf("alert: %q", e)
	}
}

func TestJSONResponsesAreGzippedWhenAsked(t *testing.T) {
	srv := httptest.NewServer(newAPI(t).Handler())
	defer srv.Close()
	get := func(enc string) *http.Response {
		req, _ := http.NewRequest("GET", srv.URL+"/api/v1/summary", nil)
		if enc != "" {
			req.Header.Set("Accept-Encoding", enc)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { resp.Body.Close() })
		return resp
	}
	resp := get("gzip")
	if resp.Header.Get("Content-Encoding") != "gzip" {
		t.Fatalf("not compressed: %v", resp.Header)
	}
	zr, err := gzip.NewReader(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	var sum map[string]any
	if err := json.NewDecoder(zr).Decode(&sum); err != nil || sum["metrics"] == nil {
		t.Errorf("decoding the compressed summary: %v %v", err, sum)
	}
	if resp := get("identity"); resp.Header.Get("Content-Encoding") != "" {
		t.Errorf("compressed without being asked")
	}
}

func TestSummaryEveryAndAcceptsGzip(t *testing.T) {
	for q, want := range map[string]time.Duration{
		"": time.Minute, "abc": time.Minute, "NaN": time.Minute, "5": time.Minute,
		"300": 5 * time.Minute, "1800.0": 30 * time.Minute, "99999": time.Hour, "-3": time.Minute,
	} {
		if got := summaryEvery(q, time.Minute); got != want {
			t.Errorf("every=%q: %v, want %v", q, got, want)
		}
	}
	for h, want := range map[string]bool{
		"": false, "gzip": true, "deflate, gzip": true, "br;q=1.0, gzip;q=0.8": true,
		"gzip;q=0": false, "gzip; q=0.0": false, "identity": false,
	} {
		r := httptest.NewRequest("GET", "/", nil)
		r.Header.Set("Accept-Encoding", h)
		if got := acceptsGzip(r); got != want {
			t.Errorf("Accept-Encoding %q: %v, want %v", h, got, want)
		}
	}
}
