// Package killswitch applies and undoes the emergency shut-offs, with the
// edit-scoped kill token that never leaves the server.
//
// Every kill saves what it changed, so a restore puts back exactly that. All
// Cloudflare changes are serialised, because several WAF kills edit one rule.
package killswitch

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/pablo-collantes/cfusage/server/internal/model"
	"github.com/pablo-collantes/cfusage/server/internal/store"
)

// RuleDescription names the one custom rule every waf_block kill shares. The
// Free plan allows five custom rules, so one rule lists every blocked host.
const RuleDescription = "cfusage kill switch"

const entrypoint = "rulesets/phases/http_request_firewall_custom/entrypoint"

// API is the part of cf.Client the service uses, so tests can fake it.
type API interface {
	REST(ctx context.Context, method, path string, body, out any) error
}

type Zone struct{ ID, Name string }

type Service struct {
	CF      API
	Store   *store.Store
	Account string
	Zones   func() []Zone
	DryRun  bool
	Emit    func(model.Kill)
	// Probe reports whether Cloudflare is answering host with its block page.
	Probe       func(ctx context.Context, host string) (bool, error)
	VerifyEvery time.Duration
	VerifyFor   time.Duration
	Now         func() time.Time
	Log         *slog.Logger
	// Ctx bounds the background work; cancelled on shutdown.
	Ctx context.Context

	mu sync.Mutex
	wg sync.WaitGroup
}

func (s *Service) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

func (s *Service) log() *slog.Logger {
	if s.Log != nil {
		return s.Log
	}
	return slog.Default()
}

func (s *Service) bg() context.Context {
	if s.Ctx != nil {
		return s.Ctx
	}
	return context.Background()
}

// Wait blocks until background apply/restore work has finished (tests, shutdown).
func (s *Service) Wait() { s.wg.Wait() }

var (
	hostRE   = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$`)
	scriptRE = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{0,62}$`)
	queueRE  = regexp.MustCompile(`^[a-f0-9]{32}$`)
)

// ErrBadTarget is a kind or target the service refuses.
var ErrBadTarget = errors.New("bad kill target")

// Validate checks that a target is well formed for its kind. This matters most
// for hosts, which are written into a rule expression.
func Validate(kind, target string) error {
	ok := false
	switch kind {
	case model.KindWAFBlock:
		ok = hostRE.MatchString(target)
	case model.KindWorkersDevOff, model.KindCronOff:
		ok = scriptRE.MatchString(target)
	case model.KindQueuePause:
		ok = queueRE.MatchString(target)
	default:
		return fmt.Errorf("%w: unknown kind %q", ErrBadTarget, kind)
	}
	if !ok {
		return fmt.Errorf("%w: %q is not a valid %s target", ErrBadTarget, target, kind)
	}
	return nil
}

func ID(kind, target string) string { return kind + ":" + target }

// Target describes a kill switch for the app. display is the name to show, e.g.
// a queue's name rather than its id.
func Target(kind, target, display string) model.KillTarget {
	if display == "" {
		display = target
	}
	t := model.KillTarget{Kind: kind, Target: target}
	switch kind {
	case model.KindWAFBlock:
		t.Label = "Block " + display
		t.Description = "Cloudflare answers every request to " + display + " with a 403 before it reaches a Worker, so blocked requests aren't billed. Takes 6-20 s to reach every data centre. WebSockets already open stay open."
	case model.KindWorkersDevOff:
		t.Label = "Turn off " + display + " on workers.dev"
		t.Description = "Stops requests through the workers.dev URL and preview URLs. Custom domains, cron triggers and queue consumers keep running."
	case model.KindCronOff:
		t.Label = "Stop " + display + "'s cron triggers"
		t.Description = "Removes the cron schedules; undo puts the same schedules back. HTTP traffic keeps flowing."
	case model.KindQueuePause:
		t.Label = "Pause queue " + display
		t.Description = "Stops delivery to consumers. Messages wait in the queue until resumed, and producers can still send (sends still count)."
	}
	return t
}

// Apply starts a kill and returns at once with state "applying". When the same
// kill is already applied it returns that one and existing=true.
func (s *Service) Apply(ctx context.Context, t model.KillTarget, reason string) (k model.Kill, existing bool, err error) {
	if err := Validate(t.Kind, t.Target); err != nil {
		return k, false, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	id := ID(t.Kind, t.Target)
	if cur, err := s.Store.CurrentKill(ctx, id); err == nil {
		return cur.Kill, true, nil
	} else if !errors.Is(err, store.ErrNotFound) {
		return k, false, err
	}
	k = model.Kill{KillTarget: t, ID: id, State: model.KillApplying, AppliedAt: model.TS(s.now()), Reason: model.Str(reason)}
	seq, err := s.Store.InsertKill(ctx, k, map[string]any{})
	if err != nil {
		return k, false, err
	}
	s.emit(k)
	s.wg.Add(1)
	go s.runApply(seq, k)
	return k, false, nil
}

// ErrBusy means the kill is still being applied.
var ErrBusy = errors.New("kill switch is still being applied; try again in a moment")

// Restore starts undoing a kill and returns at once with state "restoring".
func (s *Service) Restore(ctx context.Context, id string) (model.Kill, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	rec, err := s.Store.CurrentKill(ctx, id)
	if err != nil {
		return model.Kill{}, err
	}
	switch rec.Kill.State {
	case model.KillApplying:
		return rec.Kill, ErrBusy
	case model.KillRestoring:
		return rec.Kill, nil
	}
	k := rec.Kill
	k.State = model.KillRestoring
	k.Error = nil
	if err := s.Store.UpdateKill(ctx, rec.Seq, k, nil); err != nil {
		return k, err
	}
	s.emit(k)
	s.wg.Add(1)
	go s.runRestore(rec.Seq, k, rec.Prior)
	return k, nil
}

func (s *Service) emit(k model.Kill) {
	if s.Emit != nil {
		s.Emit(k)
	}
}

func (s *Service) save(seq int64, k model.Kill, prior any) {
	if err := s.Store.UpdateKill(context.Background(), seq, k, prior); err != nil {
		s.log().Error("saving kill", "id", k.ID, "err", err)
	}
	s.emit(k)
}

func (s *Service) runApply(seq int64, k model.Kill) {
	defer s.wg.Done()
	ctx, cancel := context.WithTimeout(s.bg(), 2*time.Minute)
	defer cancel()

	s.mu.Lock()
	prior, verified, err := s.apply(ctx, k)
	s.mu.Unlock()
	if err != nil {
		s.log().Warn("kill failed", "id", k.ID, "err", err)
		k.State = model.KillFailed
		k.Error = model.Str(err.Error())
		s.save(seq, k, nil)
		return
	}
	s.log().Info("kill applied", "id", k.ID, "dryRun", s.DryRun)
	if k.Kind == model.KindWAFBlock && !s.DryRun {
		// The rule is in place; say so, then watch it reach the edge.
		k.State = model.KillActive
		s.save(seq, k, prior)
		if s.verifyBlock(ctx, k.Target) {
			// Only if it's still active: a restore may have started meanwhile.
			if rec, ok, err := s.Store.MarkKillVerified(context.Background(), seq, s.now()); err != nil {
				s.log().Error("saving kill", "id", k.ID, "err", err)
			} else if ok {
				s.emit(rec.Kill)
			}
		}
		return
	}
	k.State = model.KillActive
	if verified {
		k.VerifiedAt = model.TSP(s.now())
	}
	s.save(seq, k, prior)
}

func (s *Service) runRestore(seq int64, k model.Kill, prior json.RawMessage) {
	defer s.wg.Done()
	ctx, cancel := context.WithTimeout(s.bg(), 2*time.Minute)
	defer cancel()

	s.mu.Lock()
	err := s.restore(ctx, k, prior)
	s.mu.Unlock()
	if err != nil {
		s.log().Warn("restore failed", "id", k.ID, "err", err)
		k.State = model.KillActive
		k.Error = model.Str("restore failed: " + err.Error())
		s.save(seq, k, nil)
		return
	}
	s.log().Info("kill restored", "id", k.ID, "dryRun", s.DryRun)
	k.State = model.KillRestored
	k.RestoredAt = model.TSP(s.now())
	s.save(seq, k, nil)
}

// --- per kind ---

type wafPrior struct {
	ZoneID    string `json:"zoneId"`
	RulesetID string `json:"rulesetId"`
}

type subdomainState struct {
	Enabled         bool `json:"enabled"`
	PreviewsEnabled bool `json:"previews_enabled"`
}

type cronPrior struct {
	Crons []string `json:"crons"`
}

type queuePrior struct {
	DeliveryPaused bool `json:"delivery_paused"`
}

// apply makes the change and returns what restore needs, and whether reading
// the state back showed the change took.
func (s *Service) apply(ctx context.Context, k model.Kill) (prior any, verified bool, err error) {
	if s.DryRun {
		s.log().Info("dry run: not changing Cloudflare", "kind", k.Kind, "target", k.Target)
		return map[string]bool{"dryRun": true}, false, nil
	}
	acct := "accounts/" + s.Account
	switch k.Kind {
	case model.KindWAFBlock:
		p, err := s.wafEdit(ctx, k.Target, true)
		return p, false, err

	case model.KindWorkersDevOff:
		path := acct + "/workers/scripts/" + k.Target + "/subdomain"
		var prev subdomainState
		if err := s.CF.REST(ctx, http.MethodGet, path, nil, &prev); err != nil {
			return nil, false, fmt.Errorf("reading workers.dev setting: %w", err)
		}
		if err := s.CF.REST(ctx, http.MethodPost, path, subdomainState{}, nil); err != nil {
			return prev, false, err
		}
		var now subdomainState
		err := s.CF.REST(ctx, http.MethodGet, path, nil, &now)
		return prev, err == nil && !now.Enabled && !now.PreviewsEnabled, nil

	case model.KindCronOff:
		path := acct + "/workers/scripts/" + k.Target + "/schedules"
		prev, err := s.crons(ctx, path)
		if err != nil {
			return nil, false, fmt.Errorf("reading schedules: %w", err)
		}
		if err := s.CF.REST(ctx, http.MethodPut, path, []map[string]string{}, nil); err != nil {
			return cronPrior{prev}, false, err
		}
		now, err := s.crons(ctx, path)
		return cronPrior{prev}, err == nil && len(now) == 0, nil

	case model.KindQueuePause:
		path := acct + "/queues/" + k.Target
		prev, err := s.queuePaused(ctx, path)
		if err != nil {
			return nil, false, fmt.Errorf("reading queue: %w", err)
		}
		if err := s.CF.REST(ctx, http.MethodPatch, path, map[string]any{"settings": map[string]any{"delivery_paused": true}}, nil); err != nil {
			return queuePrior{prev}, false, err
		}
		now, err := s.queuePaused(ctx, path)
		return queuePrior{prev}, err == nil && now, nil
	}
	return nil, false, fmt.Errorf("%w: unknown kind %q", ErrBadTarget, k.Kind)
}

func (s *Service) restore(ctx context.Context, k model.Kill, raw json.RawMessage) error {
	if s.DryRun || strings.Contains(string(raw), `"dryRun"`) {
		return nil
	}
	acct := "accounts/" + s.Account
	switch k.Kind {
	case model.KindWAFBlock:
		_, err := s.wafEdit(ctx, k.Target, false)
		return err
	case model.KindWorkersDevOff:
		var p subdomainState
		if err := json.Unmarshal(raw, &p); err != nil {
			return fmt.Errorf("saved state: %w", err)
		}
		return s.CF.REST(ctx, http.MethodPost, acct+"/workers/scripts/"+k.Target+"/subdomain", p, nil)
	case model.KindCronOff:
		var p cronPrior
		if err := json.Unmarshal(raw, &p); err != nil {
			return fmt.Errorf("saved state: %w", err)
		}
		body := []map[string]string{}
		for _, c := range p.Crons {
			body = append(body, map[string]string{"cron": c})
		}
		return s.CF.REST(ctx, http.MethodPut, acct+"/workers/scripts/"+k.Target+"/schedules", body, nil)
	case model.KindQueuePause:
		var p queuePrior
		if err := json.Unmarshal(raw, &p); err != nil {
			return fmt.Errorf("saved state: %w", err)
		}
		return s.CF.REST(ctx, http.MethodPatch, acct+"/queues/"+k.Target,
			map[string]any{"settings": map[string]any{"delivery_paused": p.DeliveryPaused}}, nil)
	}
	return fmt.Errorf("%w: unknown kind %q", ErrBadTarget, k.Kind)
}

func (s *Service) crons(ctx context.Context, path string) ([]string, error) {
	var r struct {
		Schedules []struct {
			Cron string `json:"cron"`
		} `json:"schedules"`
	}
	if err := s.CF.REST(ctx, http.MethodGet, path, nil, &r); err != nil {
		return nil, err
	}
	out := []string{}
	for _, x := range r.Schedules {
		out = append(out, x.Cron)
	}
	return out, nil
}

func (s *Service) queuePaused(ctx context.Context, path string) (bool, error) {
	var q struct {
		Settings struct {
			DeliveryPaused bool `json:"delivery_paused"`
		} `json:"settings"`
	}
	err := s.CF.REST(ctx, http.MethodGet, path, nil, &q)
	return q.Settings.DeliveryPaused, err
}

// --- WAF ---

type rule struct {
	ID          string `json:"id"`
	Description string `json:"description"`
	Expression  string `json:"expression"`
	Action      string `json:"action"`
	Enabled     bool   `json:"enabled"`
}

type ruleset struct {
	ID    string `json:"id"`
	Rules []rule `json:"rules"`
}

func (s *Service) zoneFor(host string) (Zone, error) {
	var best Zone
	if s.Zones != nil {
		for _, z := range s.Zones() {
			if (host == z.Name || strings.HasSuffix(host, "."+z.Name)) && len(z.Name) > len(best.Name) {
				best = z
			}
		}
	}
	if best.ID == "" {
		return best, fmt.Errorf("%s is not on a zone this server manages", host)
	}
	return best, nil
}

var quoted = regexp.MustCompile(`"([^"\\]+)"`)

// HostsOf reads the hosts back out of the kill-switch rule's expression.
func HostsOf(expr string) []string {
	var out []string
	for _, m := range quoted.FindAllStringSubmatch(expr, -1) {
		out = append(out, m[1])
	}
	return out
}

// Expression is the rule expression blocking hosts.
func Expression(hosts []string) string {
	q := make([]string, len(hosts))
	for i, h := range hosts {
		q[i] = `"` + h + `"`
	}
	return `(http.host in {` + strings.Join(q, " ") + `})`
}

// wafEdit adds host to (block) or removes it from (!block) the kill-switch rule,
// creating the rule at the top of the zone's custom rules when it's missing and
// deleting it when no host is left. It never replaces the entrypoint ruleset,
// which would drop the zone's other rules.
func (s *Service) wafEdit(ctx context.Context, host string, block bool) (wafPrior, error) {
	z, err := s.zoneFor(host)
	if err != nil {
		return wafPrior{}, err
	}
	var rs ruleset
	if err := s.CF.REST(ctx, http.MethodGet, "zones/"+z.ID+"/"+entrypoint, nil, &rs); err != nil {
		return wafPrior{}, fmt.Errorf("reading custom rules: %w", err)
	}
	p := wafPrior{ZoneID: z.ID, RulesetID: rs.ID}
	var cur *rule
	for i := range rs.Rules {
		if rs.Rules[i].Description == RuleDescription {
			cur = &rs.Rules[i]
			break
		}
	}
	var hosts []string
	if cur != nil {
		hosts = HostsOf(cur.Expression)
	}
	if block {
		if !slices.Contains(hosts, host) {
			hosts = append(hosts, host)
		}
	} else {
		hosts = slices.DeleteFunc(hosts, func(h string) bool { return h == host })
	}
	slices.Sort(hosts)
	base := "zones/" + z.ID + "/rulesets/" + rs.ID + "/rules"
	switch {
	case cur == nil && len(hosts) == 0:
		return p, nil // nothing blocked, nothing to remove
	case cur == nil:
		return p, s.CF.REST(ctx, http.MethodPost, base, map[string]any{
			"description": RuleDescription, "expression": Expression(hosts), "action": "block",
			"enabled": true, "position": map[string]string{"before": ""},
		}, nil)
	case len(hosts) == 0:
		return p, s.CF.REST(ctx, http.MethodDelete, base+"/"+cur.ID, nil, nil)
	default:
		return p, s.CF.REST(ctx, http.MethodPatch, base+"/"+cur.ID, map[string]any{
			"description": RuleDescription, "expression": Expression(hosts), "action": "block", "enabled": true,
		}, nil)
	}
}

// verifyBlock requests the host until Cloudflare answers with its block page.
// Testing on a throwaway Worker measured 6-20 s for a new rule to reach every data centre.
func (s *Service) verifyBlock(ctx context.Context, host string) bool {
	probe := s.Probe
	if probe == nil {
		probe = ProbeBlocked
	}
	every, total := s.VerifyEvery, s.VerifyFor
	if every == 0 {
		every = 5 * time.Second
	}
	if total == 0 {
		total = 60 * time.Second
	}
	deadline := time.Now().Add(total)
	for {
		if ok, err := probe(ctx, host); ok {
			return true
		} else if err != nil {
			s.log().Debug("block probe", "host", host, "err", err)
		}
		if time.Now().Add(every).After(deadline) {
			return false
		}
		select {
		case <-ctx.Done():
			return false
		case <-time.After(every):
		}
	}
}

var probeClient = &http.Client{
	Timeout:       10 * time.Second,
	CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
}

// blockMarkers appear in Cloudflare's block page. Status and Server alone aren't
// enough: a Worker's own 403 also carries "Server: cloudflare".
var blockMarkers = []string{"you have been blocked", "Attention Required! | Cloudflare", "error code: 1020", "cf-error-details"}

// ProbeBlocked makes one request to host and reports whether the WAF blocked it.
func ProbeBlocked(ctx context.Context, host string) (bool, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://"+host+"/", nil)
	if err != nil {
		return false, err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) cfusage-killswitch-verify")
	req.Header.Set("Accept", "text/html")
	resp, err := probeClient.Do(req)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	return IsBlockPage(resp.StatusCode, resp.Header.Get("Server"), string(body)), nil
}

func IsBlockPage(status int, server, body string) bool {
	if status != http.StatusForbidden || !strings.EqualFold(server, "cloudflare") {
		return false
	}
	for _, m := range blockMarkers {
		if strings.Contains(body, m) {
			return true
		}
	}
	return false
}
