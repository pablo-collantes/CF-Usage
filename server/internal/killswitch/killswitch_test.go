package killswitch

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/pablo-collantes/cfusage/server/internal/cf"
	"github.com/pablo-collantes/cfusage/server/internal/model"
	"github.com/pablo-collantes/cfusage/server/internal/store"
)

// fakeCF is an in-memory Cloudflare API: one zone's custom-rules entrypoint, one
// script's workers.dev setting and schedules, and one queue. It records every
// call so tests can assert what was (and wasn't) sent.
type fakeCF struct {
	mu        sync.Mutex
	calls     []string
	rules     []map[string]any
	nextID    int
	subdomain map[string]bool
	crons     []string
	paused    bool
}

const (
	zoneID  = "zone1"
	rsID    = "rs1"
	acct    = "acct1"
	queueID = "0123456789abcdef0123456789abcdef"
)

func newFake() *fakeCF {
	return &fakeCF{
		rules: []map[string]any{
			{"id": "keep1", "description": "block not US traffic", "expression": `(ip.src.country ne "US")`, "action": "block", "enabled": false},
			{"id": "keep2", "description": "AI Crawl Control", "expression": `(cf.verified_bot_category eq "AI Crawler")`, "action": "block", "enabled": true},
		},
		subdomain: map[string]bool{"enabled": true, "previews_enabled": true},
		crons:     []string{"* * * * *", "0 23 * * *"},
	}
}

func ok(w http.ResponseWriter, result any) {
	_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "errors": []any{}, "result": result})
}

func (f *fakeCF) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if r.Header.Get("Authorization") != "Bearer kill-secret" {
		w.WriteHeader(403)
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "errors": []any{map[string]any{"code": 10000, "message": "auth"}}})
		return
	}
	body, _ := io.ReadAll(r.Body)
	f.calls = append(f.calls, r.Method+" "+r.URL.Path+" "+string(body))
	p := r.URL.Path
	ruleset := func() map[string]any { return map[string]any{"id": rsID, "rules": f.rules} }
	switch {
	case p == "/zones/"+zoneID+"/rulesets/phases/http_request_firewall_custom/entrypoint" && r.Method == "GET":
		ok(w, ruleset())
	case p == "/zones/"+zoneID+"/rulesets/"+rsID+"/rules" && r.Method == "POST":
		var rule map[string]any
		_ = json.Unmarshal(body, &rule)
		f.nextID++
		rule["id"] = "new" + string(rune('0'+f.nextID))
		pos, _ := rule["position"].(map[string]any)
		delete(rule, "position")
		if pos != nil && pos["before"] == "" {
			f.rules = append([]map[string]any{rule}, f.rules...)
		} else {
			f.rules = append(f.rules, rule)
		}
		ok(w, ruleset())
	case strings.HasPrefix(p, "/zones/"+zoneID+"/rulesets/"+rsID+"/rules/"):
		id := strings.TrimPrefix(p, "/zones/"+zoneID+"/rulesets/"+rsID+"/rules/")
		for i, rule := range f.rules {
			if rule["id"] != id {
				continue
			}
			switch r.Method {
			case "PATCH":
				var upd map[string]any
				_ = json.Unmarshal(body, &upd)
				for k, v := range upd {
					rule[k] = v
				}
			case "DELETE":
				f.rules = append(f.rules[:i], f.rules[i+1:]...)
			}
			ok(w, ruleset())
			return
		}
		w.WriteHeader(404)
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false})
	case p == "/accounts/"+acct+"/workers/scripts/api/subdomain":
		if r.Method == "POST" {
			var s map[string]bool
			_ = json.Unmarshal(body, &s)
			f.subdomain = s
		}
		ok(w, f.subdomain)
	case p == "/accounts/"+acct+"/workers/scripts/api/schedules":
		if r.Method == "PUT" {
			var s []map[string]string
			_ = json.Unmarshal(body, &s)
			f.crons = nil
			for _, x := range s {
				f.crons = append(f.crons, x["cron"])
			}
		}
		out := []map[string]string{}
		for _, c := range f.crons {
			out = append(out, map[string]string{"cron": c})
		}
		ok(w, map[string]any{"schedules": out})
	case p == "/accounts/"+acct+"/queues/"+queueID:
		if r.Method == "PATCH" {
			var s struct {
				Settings map[string]any `json:"settings"`
			}
			_ = json.Unmarshal(body, &s)
			f.paused, _ = s.Settings["delivery_paused"].(bool)
		}
		settings := map[string]any{"delivery_delay": 0}
		if f.paused {
			settings["delivery_paused"] = true
		}
		ok(w, map[string]any{"queue_id": queueID, "settings": settings})
	default:
		w.WriteHeader(405)
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "errors": []any{map[string]any{"code": 1, "message": "unexpected " + r.Method + " " + p}}})
	}
}

func (f *fakeCF) callLog() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string{}, f.calls...)
}

// state reads the fake's state under its lock (the race detector can't see the
// HTTP round trip as synchronisation).
func (f *fakeCF) state() (rules []map[string]any, sub map[string]bool, crons []string, paused bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	b, _ := json.Marshal(f.rules)
	_ = json.Unmarshal(b, &rules)
	sub = map[string]bool{}
	for k, v := range f.subdomain {
		sub[k] = v
	}
	return rules, sub, append([]string{}, f.crons...), f.paused
}

func (f *fakeCF) ourRule() map[string]any {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, r := range f.rules {
		if r["description"] == RuleDescription {
			return r
		}
	}
	return nil
}

type harness struct {
	svc    *Service
	fake   *fakeCF
	events []model.Kill
	mu     sync.Mutex
	probes int
}

func setup(t *testing.T, probeOKAfter int) *harness {
	t.Helper()
	dir := t.TempDir()
	tok := filepath.Join(dir, "kill_token")
	if err := os.WriteFile(tok, []byte("kill-secret\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	st, err := store.Open(filepath.Join(dir, "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	h := &harness{fake: newFake()}
	srv := httptest.NewServer(h.fake)
	t.Cleanup(srv.Close)
	client := cf.New(tok)
	client.Base = srv.URL
	h.svc = &Service{
		CF: client, Store: st, Account: acct,
		Zones: func() []Zone { return []Zone{{ID: zoneID, Name: "example.dev"}} },
		Emit: func(k model.Kill) {
			h.mu.Lock()
			h.events = append(h.events, k)
			h.mu.Unlock()
		},
		Probe: func(ctx context.Context, host string) (bool, error) {
			h.mu.Lock()
			defer h.mu.Unlock()
			h.probes++
			return h.probes >= probeOKAfter, nil
		},
		VerifyEvery: 5 * time.Millisecond, VerifyFor: 500 * time.Millisecond,
	}
	return h
}

func (h *harness) states(id string) []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	var out []string
	for _, k := range h.events {
		if k.ID == id {
			out = append(out, k.State)
		}
	}
	return out
}

func apply(t *testing.T, h *harness, kind, target string) model.Kill {
	t.Helper()
	k, existing, err := h.svc.Apply(context.Background(), Target(kind, target, ""), "test")
	if err != nil || existing {
		t.Fatalf("Apply(%s %s): existing=%v err=%v", kind, target, existing, err)
	}
	if k.State != model.KillApplying {
		t.Fatalf("Apply returned state %s", k.State)
	}
	h.svc.Wait()
	cur, err := h.svc.Store.CurrentKill(context.Background(), k.ID)
	if err != nil {
		t.Fatalf("CurrentKill: %v", err)
	}
	return cur.Kill
}

func restore(t *testing.T, h *harness, id string) model.Kill {
	t.Helper()
	if _, err := h.svc.Restore(context.Background(), id); err != nil {
		t.Fatalf("Restore(%s): %v", id, err)
	}
	h.svc.Wait()
	recs, _ := h.svc.Store.Kills(context.Background(), 50)
	for _, r := range recs {
		if r.Kill.ID == id {
			return r.Kill
		}
	}
	t.Fatalf("no record for %s", id)
	return model.Kill{}
}

func TestWAFBlockOneSharedRule(t *testing.T) {
	h := setup(t, 2)
	a := apply(t, h, model.KindWAFBlock, "api.example.dev")
	if a.State != model.KillActive || a.VerifiedAt == nil {
		t.Fatalf("after apply: state %s verified %v", a.State, a.VerifiedAt)
	}
	rule := h.fake.ourRule()
	if rule == nil || rule["expression"] != `(http.host in {"api.example.dev"})` || rule["action"] != "block" {
		t.Fatalf("rule after first block: %v", rule)
	}
	if rules, _, _, _ := h.fake.state(); rules[0]["description"] != RuleDescription {
		t.Errorf("kill rule isn't first: %v", rules[0])
	}

	apply(t, h, model.KindWAFBlock, "www.example.dev")
	if got := h.fake.ourRule()["expression"]; got != `(http.host in {"api.example.dev" "www.example.dev"})` {
		t.Errorf("rule after second block: %v", got)
	}
	if rules, _, _, _ := h.fake.state(); len(rules) != 3 {
		t.Errorf("%d rules, want the 2 originals plus 1", len(rules))
	}

	r := restore(t, h, "waf_block:api.example.dev")
	if r.State != model.KillRestored || r.RestoredAt == nil {
		t.Errorf("after restore: %+v", r)
	}
	if got := h.fake.ourRule()["expression"]; got != `(http.host in {"www.example.dev"})` {
		t.Errorf("rule after first restore: %v", got)
	}
	restore(t, h, "waf_block:www.example.dev")
	if h.fake.ourRule() != nil {
		t.Error("kill rule left behind after the last host was restored")
	}
	if rules, _, _, _ := h.fake.state(); len(rules) != 2 || rules[0]["id"] != "keep1" || rules[1]["id"] != "keep2" ||
		rules[0]["enabled"] != false || rules[1]["enabled"] != true {
		t.Errorf("the zone's own rules changed: %v", rules)
	}
	for _, c := range h.fake.callLog() {
		if strings.HasPrefix(c, "PUT ") {
			t.Errorf("the entrypoint must never be replaced: %s", c)
		}
	}
	if got := h.states("waf_block:api.example.dev"); strings.Join(got, ",") != "applying,active,active,restoring,restored" {
		t.Errorf("event states = %v", got)
	}
}

func TestWAFUnverified(t *testing.T) {
	h := setup(t, 1<<30) // the probe never sees the block page
	k := apply(t, h, model.KindWAFBlock, "api.example.dev")
	if k.State != model.KillActive || k.VerifiedAt != nil {
		t.Errorf("unverified block: state %s verified %v", k.State, k.VerifiedAt)
	}
}

func TestWAFRejectsForeignHostAndInjection(t *testing.T) {
	h := setup(t, 1)
	// A host on no managed zone fails without touching the rules.
	if _, _, err := h.svc.Apply(context.Background(), Target(model.KindWAFBlock, "other.org", ""), ""); err != nil {
		t.Fatal(err)
	}
	h.svc.Wait()
	recs, _ := h.svc.Store.Kills(context.Background(), 5)
	if recs[0].Kill.State != model.KillFailed || recs[0].Kill.Error == nil {
		t.Errorf("foreign host: %+v", recs[0].Kill)
	}
	for _, bad := range []string{`a.example.dev" or true or "`, "UPPER.example.dev", "nodot", "a..example.dev", ""} {
		if _, _, err := h.svc.Apply(context.Background(), Target(model.KindWAFBlock, bad, ""), ""); err == nil {
			t.Errorf("accepted host %q", bad)
		}
	}
	if _, _, err := h.svc.Apply(context.Background(), Target("delete_everything", "x", ""), ""); err == nil {
		t.Error("accepted an unknown kind")
	}
}

func TestApplyTwiceReturnsExisting(t *testing.T) {
	h := setup(t, 1)
	first := apply(t, h, model.KindWAFBlock, "api.example.dev")
	k, existing, err := h.svc.Apply(context.Background(), Target(model.KindWAFBlock, "api.example.dev", ""), "")
	if err != nil || !existing || k.AppliedAt != first.AppliedAt {
		t.Errorf("second apply: existing=%v err=%v %+v", existing, err, k)
	}
	if _, err := h.svc.Restore(context.Background(), "waf_block:nope.example.dev"); err != store.ErrNotFound {
		t.Errorf("restore of unknown kill: %v", err)
	}
}

func TestWorkersDevOff(t *testing.T) {
	h := setup(t, 1)
	k := apply(t, h, model.KindWorkersDevOff, "api")
	if k.State != model.KillActive || k.VerifiedAt == nil {
		t.Fatalf("apply: %+v", k)
	}
	if _, sub, _, _ := h.fake.state(); sub["enabled"] || sub["previews_enabled"] {
		t.Errorf("still on: %v", sub)
	}
	restore(t, h, k.ID)
	if _, sub, _, _ := h.fake.state(); !sub["enabled"] || !sub["previews_enabled"] {
		t.Errorf("not restored to the saved setting: %v", sub)
	}
}

func TestCronOff(t *testing.T) {
	h := setup(t, 1)
	k := apply(t, h, model.KindCronOff, "api")
	if _, _, crons, _ := h.fake.state(); k.State != model.KillActive || k.VerifiedAt == nil || len(crons) != 0 {
		t.Fatalf("apply: %+v crons %v", k, crons)
	}
	restore(t, h, k.ID)
	if _, _, crons, _ := h.fake.state(); strings.Join(crons, "|") != "* * * * *|0 23 * * *" {
		t.Errorf("crons after restore: %v", crons)
	}
}

func TestQueuePause(t *testing.T) {
	h := setup(t, 1)
	k := apply(t, h, model.KindQueuePause, queueID)
	if _, _, _, paused := h.fake.state(); k.State != model.KillActive || k.VerifiedAt == nil || !paused {
		t.Fatalf("apply: %+v paused %v", k, paused)
	}
	restore(t, h, k.ID)
	if _, _, _, paused := h.fake.state(); paused {
		t.Error("still paused after restore")
	}
}

func TestFailedRestoreStaysActive(t *testing.T) {
	h := setup(t, 1)
	k := apply(t, h, model.KindCronOff, "api")
	h.svc.CF.(*cf.Client).TokenFile = "/nonexistent" // the next call can't authenticate
	r := restore(t, h, k.ID)
	if r.State != model.KillActive || r.Error == nil || !strings.HasPrefix(*r.Error, "restore failed") {
		t.Errorf("failed restore: %+v", r)
	}
}

func TestDryRunCallsNothing(t *testing.T) {
	h := setup(t, 1)
	h.svc.DryRun = true
	k := apply(t, h, model.KindWAFBlock, "api.example.dev")
	if k.State != model.KillActive {
		t.Errorf("dry run apply: %+v", k)
	}
	restore(t, h, k.ID)
	if calls := h.fake.callLog(); len(calls) != 0 {
		t.Errorf("dry run called Cloudflare: %v", calls)
	}
}

func TestHostsAndBlockPage(t *testing.T) {
	if got := HostsOf(Expression([]string{"a.example.dev", "b.example.dev"})); strings.Join(got, ",") != "a.example.dev,b.example.dev" {
		t.Errorf("round trip: %v", got)
	}
	if !IsBlockPage(403, "cloudflare", "<title>Attention Required! | Cloudflare</title> Sorry, you have been blocked") {
		t.Error("block page not recognised")
	}
	if IsBlockPage(403, "cloudflare", `{"error":"forbidden"}`) {
		t.Error("a Worker's own 403 taken for a block")
	}
	if IsBlockPage(200, "cloudflare", "you have been blocked") {
		t.Error("200 taken for a block")
	}
}
