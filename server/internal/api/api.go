// Package api serves the v1 HTTP API (docs/API.md) behind `tailscale serve`.
package api

import (
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"math"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/pablo-collantes/cfusage/server/internal/killswitch"
	"github.com/pablo-collantes/cfusage/server/internal/model"
	"github.com/pablo-collantes/cfusage/server/internal/monitor"
	"github.com/pablo-collantes/cfusage/server/internal/store"
)

type API struct {
	Mon           *monitor.Monitor
	Kill          *killswitch.Service
	Store         *store.Store
	Hub           *Hub
	Log           *slog.Logger
	AllowedLogins []string
	// Stream timings. The defaults are the contract's: a ping after 70 s of silence,
	// 2 h streams, and summaries no more often than every 60 s.
	PingEvery, StreamFor, MinEvery time.Duration
}

func (a *API) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, 200, a.Mon.Health()) })
	mux.HandleFunc("GET /api/v1/summary", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, 200, a.Mon.Summary()) })
	mux.HandleFunc("GET /api/v1/metrics/{key}", func(w http.ResponseWriter, r *http.Request) {
		v, err := a.Mon.MetricDetail(r.Context(), r.PathValue("key"), r.URL.Query().Get("range"))
		reply(w, v, err)
	})
	mux.HandleFunc("GET /api/v1/workers", func(w http.ResponseWriter, r *http.Request) {
		v, err := a.Mon.Workers(r.Context(), r.URL.Query().Get("range"))
		reply(w, v, err)
	})
	mux.HandleFunc("GET /api/v1/workers/{name}", func(w http.ResponseWriter, r *http.Request) {
		v, err := a.Mon.WorkerDetail(r.Context(), r.PathValue("name"), r.URL.Query().Get("range"))
		reply(w, v, err)
	})
	mux.HandleFunc("GET /api/v1/sites", func(w http.ResponseWriter, r *http.Request) {
		v, err := a.Mon.Sites(r.Context(), r.URL.Query().Get("range"))
		reply(w, v, err)
	})
	mux.HandleFunc("GET /api/v1/sites/{host}", func(w http.ResponseWriter, r *http.Request) {
		v, err := a.Mon.SiteDetail(r.Context(), r.PathValue("host"), r.URL.Query().Get("range"))
		reply(w, v, err)
	})
	mux.HandleFunc("GET /api/v1/alerts", func(w http.ResponseWriter, r *http.Request) {
		limit := 50
		if n, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && n > 0 && n <= 500 {
			limit = n
		}
		v, err := a.Store.Alerts(r.Context(), limit, false)
		reply(w, v, err)
	})
	mux.HandleFunc("POST /api/v1/alerts/{id}/ack", a.mutating(func(w http.ResponseWriter, r *http.Request) {
		al, err := a.Store.AckAlert(r.Context(), r.PathValue("id"), time.Now())
		if errors.Is(err, store.ErrNotFound) {
			writeErr(w, 404, "no such alert")
			return
		}
		if err == nil {
			a.Mon.RefreshLists(context.Background())
		}
		reply(w, al, err)
	}))
	mux.HandleFunc("GET /api/v1/kill", func(w http.ResponseWriter, r *http.Request) {
		recs, err := a.Store.Kills(r.Context(), 50)
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		out := model.KillList{Available: a.Mon.AvailableKills(r.Context()), Kills: []model.Kill{}}
		for _, k := range recs {
			out.Kills = append(out.Kills, k.Kill)
		}
		writeJSON(w, 200, out)
	})
	mux.HandleFunc("POST /api/v1/kill", a.mutating(a.applyKill))
	mux.HandleFunc("DELETE /api/v1/kill/{id}", a.mutating(func(w http.ResponseWriter, r *http.Request) {
		k, err := a.Kill.Restore(r.Context(), r.PathValue("id"))
		switch {
		case errors.Is(err, store.ErrNotFound):
			writeErr(w, 404, "no applied kill switch with that id")
		case errors.Is(err, killswitch.ErrBusy):
			writeErr(w, 409, err.Error())
		case err != nil:
			writeErr(w, 500, err.Error())
		default:
			writeJSON(w, 202, k)
		}
	}))
	mux.HandleFunc("GET /api/v1/settings", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, 200, a.Mon.Settings()) })
	mux.HandleFunc("PUT /api/v1/settings", a.mutating(func(w http.ResponseWriter, r *http.Request) {
		var s model.Settings
		if err := decode(r, &s); err != nil {
			writeErr(w, 400, err.Error())
			return
		}
		out, err := a.Mon.SetSettings(r.Context(), s)
		if err != nil {
			writeErr(w, 400, err.Error())
			return
		}
		writeJSON(w, 200, out)
	}))
	mux.HandleFunc("GET /api/v1/events", a.events)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { writeErr(w, 404, "no such endpoint") })
	return a.logRequests(compress(mux))
}

func (a *API) applyKill(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Kind   string `json:"kind"`
		Target string `json:"target"`
		Reason string `json:"reason"`
	}
	if err := decode(r, &body); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := killswitch.Validate(body.Kind, body.Target); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	// Only targets the server offered, so a typo can't block an unrelated host.
	t, ok := a.Mon.KillTargetFor(r.Context(), body.Kind, body.Target)
	if !ok {
		if cur, err := a.Store.CurrentKill(r.Context(), killswitch.ID(body.Kind, body.Target)); err == nil {
			writeJSON(w, 200, cur.Kill)
			return
		}
		writeErr(w, 400, "not one of the available kill switches (GET /api/v1/kill)")
		return
	}
	k, existing, err := a.Kill.Apply(r.Context(), t, body.Reason)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	login := r.Header.Get("Tailscale-User-Login")
	a.Log.Warn("kill switch requested", "id", k.ID, "by", login, "existing", existing)
	if existing {
		writeJSON(w, 200, k)
		return
	}
	writeJSON(w, 202, k)
}

func (a *API) events(w http.ResponseWriter, r *http.Request) {
	rc := http.NewResponseController(w)
	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("Cache-Control", "no-cache")
	h.Set("X-Accel-Buffering", "no")
	// One compressor for the whole stream: each summary mostly repeats the one
	// before, so after the first it shrinks from about 22 KB to about 350 bytes.
	// Level 9, because at the default level a ping flushed just before a summary
	// stops Go's compressor matching the previous one (3.5 KB instead of 400 B).
	var out io.Writer = w
	flush := rc.Flush
	if acceptsGzip(r) {
		h.Set("Content-Encoding", "gzip")
		h.Add("Vary", "Accept-Encoding")
		gz, _ := gzip.NewWriterLevel(w, gzip.BestCompression)
		defer gz.Close()
		out = gz
		flush = func() error {
			if err := gz.Flush(); err != nil {
				return err
			}
			return rc.Flush()
		}
	}
	w.WriteHeader(200)

	pingEvery, streamFor, minEvery := a.PingEvery, a.StreamFor, a.MinEvery
	if pingEvery == 0 {
		// Past the 60 s poll rhythm, so pings don't land just before a summary,
		// and inside the app's 90 s read timeout.
		pingEvery = 70 * time.Second
	}
	if streamFor == 0 {
		streamFor = 2 * time.Hour
	}
	if minEvery == 0 {
		minEvery = 60 * time.Second
	}
	every := summaryEvery(r.URL.Query().Get("every"), minEvery)

	ch, cancel := a.Hub.Subscribe()
	defer cancel()
	ping := time.NewTimer(pingEvery) // only fires after pingEvery with nothing sent
	defer ping.Stop()
	end := time.NewTimer(streamFor) // bounds how long a dead connection can linger
	defer end.Stop()
	send := func(f func() error) bool {
		_ = rc.SetWriteDeadline(time.Now().Add(30 * time.Second))
		if f() != nil || flush() != nil {
			return false
		}
		ping.Reset(pingEvery)
		return true
	}
	// Summaries follow the polls, so the phone is never more than one poll behind:
	// one goes out if `every` has passed since the last, give or take half a poll
	// interval (30 s) for poll timing. Others are held, and a held one goes out if
	// nothing newer comes within that tolerance after the interval, which only
	// happens when polls stop. The summary sent on connect doesn't start the
	// interval, so the first poll after it sets the rhythm.
	tol := min(30*time.Second, every/2)
	var held []byte
	var gate <-chan time.Time
	var last time.Time
	sendSummary := func(data []byte) bool {
		held, gate, last = nil, nil, time.Now()
		return send(func() error { return WriteEvent(out, "summary", data) })
	}
	sum, _ := json.Marshal(a.Mon.Summary())
	if !send(func() error { return WriteEvent(out, "summary", sum) }) {
		return
	}
	for {
		select {
		case <-r.Context().Done():
			return
		case <-end.C:
			return
		case <-ping.C:
			if !send(func() error { return WriteComment(out, "ping") }) {
				return
			}
		case <-gate:
			if !sendSummary(held) {
				return
			}
		case ev := <-ch:
			switch {
			case ev.Name == "summary" && time.Since(last) < every-tol:
				held = ev.Data
				if gate == nil {
					gate = time.After(time.Until(last.Add(every + tol)))
				}
			case ev.Name == "summary":
				if !sendSummary(ev.Data) {
					return
				}
			default:
				// Alerts and kills go out at once. The monitor publishes the summary
				// they belong to right after them, and that one goes out at once too,
				// so the phone's widgets match the alert.
				if !send(func() error { return WriteEvent(out, ev.Name, ev.Data) }) {
					return
				}
				last = time.Time{}
			}
		}
	}
}

// summaryEvery reads the stream's `every` (seconds), clamped to [min, 1 h].
func summaryEvery(q string, min time.Duration) time.Duration {
	v, err := strconv.ParseFloat(q, 64)
	if err != nil || math.IsNaN(v) {
		return min
	}
	d := time.Duration(math.Min(v, 3600) * float64(time.Second))
	return max(d, min)
}

func acceptsGzip(r *http.Request) bool {
	for _, part := range strings.Split(r.Header.Get("Accept-Encoding"), ",") {
		name, params, _ := strings.Cut(part, ";")
		if strings.TrimSpace(name) != "gzip" {
			continue
		}
		if q, ok := strings.CutPrefix(strings.TrimSpace(params), "q="); ok {
			v, err := strconv.ParseFloat(q, 64)
			return err != nil || v > 0
		}
		return true
	}
	return false
}

// compress gzips responses for clients that ask; OkHttp always does. A summary
// shrinks from about 24 KB to about 4 KB. The event stream compresses itself so
// it can flush after every event.
func compress(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/events" || !acceptsGzip(r) {
			next.ServeHTTP(w, r)
			return
		}
		w.Header().Set("Content-Encoding", "gzip")
		w.Header().Add("Vary", "Accept-Encoding")
		gz := gzip.NewWriter(w)
		defer gz.Close()
		next.ServeHTTP(gzipWriter{ResponseWriter: w, gz: gz}, r)
	})
}

type gzipWriter struct {
	http.ResponseWriter
	gz *gzip.Writer
}

func (g gzipWriter) Write(b []byte) (int, error) { return g.gz.Write(b) }
func (g gzipWriter) Unwrap() http.ResponseWriter { return g.ResponseWriter }

// mutating requires a tailnet login in AllowedLogins. `tailscale serve` adds
// Tailscale-User-Login (and X-Forwarded-For) to every proxied request from a
// user's device. A request with neither came straight to the listener, which
// only loopback can reach, for local testing. A proxied request without a login
// (a tagged device) is refused.
func (a *API) mutating(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if login := r.Header.Get("Tailscale-User-Login"); login != "" {
			for _, l := range a.AllowedLogins {
				if l == login {
					h(w, r)
					return
				}
			}
			writeErr(w, 403, login+" may not change anything here")
			return
		}
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		ip := net.ParseIP(host)
		if r.Header.Get("X-Forwarded-For") == "" && ip != nil && ip.IsLoopback() {
			h(w, r)
			return
		}
		writeErr(w, 403, "changes need a tailnet login (Tailscale-User-Login)")
	}
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (s *statusWriter) WriteHeader(c int)           { s.status = c; s.ResponseWriter.WriteHeader(c) }
func (s *statusWriter) Unwrap() http.ResponseWriter { return s.ResponseWriter }

func (a *API) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t0 := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: 200}
		next.ServeHTTP(sw, r)
		a.Log.Debug("http", "method", r.Method, "path", r.URL.Path, "status", sw.status, "took", time.Since(t0).Round(time.Millisecond))
	})
}

func decode(r *http.Request, v any) error {
	d := json.NewDecoder(http.MaxBytesReader(nil, r.Body, 64<<10))
	d.DisallowUnknownFields()
	if err := d.Decode(v); err != nil {
		return errors.New("invalid JSON body: " + err.Error())
	}
	return nil
}

func reply[T any](w http.ResponseWriter, v T, err error) {
	switch {
	case errors.Is(err, monitor.ErrBadRequest):
		writeErr(w, 400, err.Error())
	case errors.Is(err, monitor.ErrNotFound):
		writeErr(w, 404, err.Error())
	case err != nil:
		writeErr(w, 502, err.Error())
	default:
		writeJSON(w, 200, v)
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
