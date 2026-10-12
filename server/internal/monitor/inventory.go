package monitor

import (
	"context"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/pablo-collantes/cfusage/server/internal/killswitch"
	"github.com/pablo-collantes/cfusage/server/internal/model"
	"github.com/pablo-collantes/cfusage/server/internal/usage"
)

// Inventory is the read-only picture of the account that turns analytics IDs into
// names and maps resources to the kill switches that can stop them.
type Inventory struct {
	Names     usage.Names
	Zones     []killswitch.Zone
	Domains   []Domain
	Scripts   map[string]*Script
	Queues    []Queue
	FetchedAt time.Time
}

type Domain struct{ Host, Service, ZoneID, ZoneName string }

type Script struct {
	Name       string
	WorkersDev *bool // nil when the read token couldn't tell
	Crons      []string
	CronsKnown bool
	Binds      []Binding
}

// Binding is a resource a script uses, named the way the metrics name it.
type Binding struct{ Kind, Resource string } // Kind: d1 | r2 | kv | queue | do

type Queue struct {
	ID, Name             string
	Producers, Consumers []string
}

func (inv *Inventory) zoneFor(host string) (killswitch.Zone, bool) {
	var best killswitch.Zone
	for _, z := range inv.Zones {
		if (host == z.Name || strings.HasSuffix(host, "."+z.Name)) && len(z.Name) > len(best.Name) {
			best = z
		}
	}
	return best, best.ID != ""
}

func (inv *Inventory) hostsOf(script string) []string {
	out := []string{}
	for _, d := range inv.Domains {
		if d.Service == script {
			out = append(out, d.Host)
		}
	}
	sort.Strings(out)
	return out
}

func (inv *Inventory) serviceOf(host string) *string {
	for _, d := range inv.Domains {
		if d.Host == host {
			s := "worker:" + d.Service
			return &s
		}
	}
	return nil
}

func (inv *Inventory) queueByName(name string) (Queue, bool) {
	for _, q := range inv.Queues {
		if q.Name == name || q.ID == name {
			return q, true
		}
	}
	return Queue{}, false
}

// scriptsUsing returns the scripts bound to a resource of a kind.
func (inv *Inventory) scriptsUsing(kind, resource string) []string {
	var out []string
	for _, s := range inv.Scripts {
		for _, b := range s.Binds {
			if b.Kind == kind && b.Resource == resource {
				out = append(out, s.Name)
				break
			}
		}
	}
	sort.Strings(out)
	return out
}

// loadInventory reads names, domains, scripts and their bindings with the read
// token. A list the token can't read is left empty rather than failing.
func (m *Monitor) loadInventory(ctx context.Context) (*Inventory, error) {
	inv := &Inventory{
		Names:   usage.Names{D1: map[string]string{}, Queues: map[string]string{}, KV: map[string]string{}, DO: map[string]string{}},
		Scripts: map[string]*Script{},
	}
	acct := "accounts/" + m.cfg.Account
	get := func(path string, out any) bool {
		if err := m.read.REST(ctx, http.MethodGet, path, nil, out); err != nil {
			m.log.Warn("inventory", "path", path, "err", err)
			return false
		}
		return true
	}

	var zones []struct{ ID, Name string }
	if !get("zones?per_page=50", &zones) {
		return nil, fmt.Errorf("listing zones failed")
	}
	for _, z := range zones {
		if len(m.cfg.ZoneIDs) == 0 || contains(m.cfg.ZoneIDs, z.ID) {
			inv.Zones = append(inv.Zones, killswitch.Zone{ID: z.ID, Name: z.Name})
		}
	}

	var d1 []struct{ UUID, Name string }
	get(acct+"/d1/database?per_page=100", &d1)
	for _, d := range d1 {
		inv.Names.D1[d.UUID] = d.Name
	}
	var queues []struct {
		QueueID   string `json:"queue_id"`
		QueueName string `json:"queue_name"`
		Consumers []struct{ Script string }
		Producers []struct{ Script string }
	}
	get(acct+"/queues?per_page=100", &queues)
	for _, q := range queues {
		inv.Names.Queues[q.QueueID] = q.QueueName
		qq := Queue{ID: q.QueueID, Name: q.QueueName}
		for _, c := range q.Consumers {
			if c.Script != "" {
				qq.Consumers = append(qq.Consumers, c.Script)
			}
		}
		for _, p := range q.Producers {
			if p.Script != "" {
				qq.Producers = append(qq.Producers, p.Script)
			}
		}
		inv.Queues = append(inv.Queues, qq)
	}
	var kv []struct{ ID, Title string }
	get(acct+"/storage/kv/namespaces?per_page=100", &kv)
	for _, n := range kv {
		inv.Names.KV[strings.ReplaceAll(n.ID, "-", "")] = n.Title
	}
	var dos []struct{ ID, Name, Script, Class string }
	get(acct+"/workers/durable_objects/namespaces?per_page=100", &dos)
	for _, n := range dos {
		class := n.Class
		if class == "" {
			class = n.Name
		}
		inv.Names.DO[n.ID] = n.Script + ":" + class
	}
	var domains []struct {
		Hostname, Service string
		ZoneID            string `json:"zone_id"`
		ZoneName          string `json:"zone_name"`
	}
	get(acct+"/workers/domains", &domains)
	for _, d := range domains {
		inv.Domains = append(inv.Domains, Domain{Host: d.Hostname, Service: d.Service, ZoneID: d.ZoneID, ZoneName: d.ZoneName})
	}

	var scripts []struct{ ID string }
	get(acct+"/workers/scripts", &scripts)
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 4)
	for _, sc := range scripts {
		s := &Script{Name: sc.ID}
		inv.Scripts[sc.ID] = s
		wg.Add(1)
		go func() {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			m.loadScript(ctx, inv, s, &mu)
		}()
	}
	wg.Wait()
	inv.FetchedAt = time.Now().UTC()
	return inv, nil
}

func (m *Monitor) loadScript(ctx context.Context, inv *Inventory, s *Script, mu *sync.Mutex) {
	base := "accounts/" + m.cfg.Account + "/workers/scripts/" + s.Name
	// Only the resource identifiers of bindings are decoded: settings also carries
	// plain-text variables, which this server has no business keeping.
	var settings struct {
		Bindings []struct {
			Type        string `json:"type"`
			ID          string `json:"id"`
			DatabaseID  string `json:"database_id"`
			BucketName  string `json:"bucket_name"`
			NamespaceID string `json:"namespace_id"`
			QueueName   string `json:"queue_name"`
		} `json:"bindings"`
	}
	var binds []Binding
	if err := m.read.REST(ctx, http.MethodGet, base+"/settings", nil, &settings); err == nil {
		mu.Lock()
		for _, b := range settings.Bindings {
			switch b.Type {
			case "d1":
				id := b.DatabaseID
				if id == "" {
					id = b.ID
				}
				binds = append(binds, Binding{"d1", inv.Names.D1[id]})
			case "r2_bucket":
				binds = append(binds, Binding{"r2", b.BucketName})
			case "kv_namespace":
				name := inv.Names.KV[strings.ReplaceAll(b.NamespaceID, "-", "")]
				if name == "" {
					name = b.NamespaceID
				}
				binds = append(binds, Binding{"kv", name})
			case "queue":
				binds = append(binds, Binding{"queue", b.QueueName})
			case "durable_object_namespace":
				if n, ok := inv.Names.DO[b.NamespaceID]; ok {
					binds = append(binds, Binding{"do", n})
				}
			}
		}
		mu.Unlock()
	}
	var sub struct {
		Enabled         bool `json:"enabled"`
		PreviewsEnabled bool `json:"previews_enabled"`
	}
	var dev *bool
	if err := m.read.REST(ctx, http.MethodGet, base+"/subdomain", nil, &sub); err == nil {
		on := sub.Enabled || sub.PreviewsEnabled
		dev = &on
	}
	var sched struct {
		Schedules []struct{ Cron string } `json:"schedules"`
	}
	var crons []string
	known := false
	if err := m.read.REST(ctx, http.MethodGet, base+"/schedules", nil, &sched); err == nil {
		known = true
		for _, c := range sched.Schedules {
			crons = append(crons, c.Cron)
		}
	}
	mu.Lock()
	s.Binds, s.WorkersDev, s.Crons, s.CronsKnown = binds, dev, crons, known
	mu.Unlock()
}

// --- kill switch targets ---

// scriptKills are the kill switches that stop a script: a WAF block per custom
// domain, its workers.dev route, its cron triggers. open lists kill ids that are
// applied now, which stay listed even though the setting they changed is off.
func (inv *Inventory) scriptKills(script string, open map[string]bool) []model.KillTarget {
	out := []model.KillTarget{}
	for _, h := range inv.hostsOf(script) {
		if _, ok := inv.zoneFor(h); ok {
			out = append(out, killswitch.Target(model.KindWAFBlock, h, ""))
		}
	}
	s := inv.Scripts[script]
	if s == nil {
		return out
	}
	if s.WorkersDev == nil || *s.WorkersDev || open[killswitch.ID(model.KindWorkersDevOff, script)] {
		out = append(out, killswitch.Target(model.KindWorkersDevOff, script, ""))
	}
	if !s.CronsKnown || len(s.Crons) > 0 || open[killswitch.ID(model.KindCronOff, script)] {
		out = append(out, killswitch.Target(model.KindCronOff, script, ""))
	}
	return out
}

func (inv *Inventory) queueKill(q Queue) model.KillTarget {
	return killswitch.Target(model.KindQueuePause, q.ID, q.Name)
}

// resourceKills maps a metric's contributor to the kill switches that stop it.
func (inv *Inventory) resourceKills(metric, resource string, open map[string]bool) []model.KillTarget {
	switch usage.ResourceKind(metric) {
	case "script":
		if strings.HasPrefix(resource, usage.PagesPrefix) {
			return nil // Pages projects can't be mapped to hosts without Pages Read
		}
		return inv.scriptKills(resource, open)
	case "do":
		script, _, _ := strings.Cut(resource, ":")
		return inv.scriptKills(script, open)
	case "queue":
		q, ok := inv.queueByName(resource)
		if !ok {
			return nil
		}
		out := []model.KillTarget{inv.queueKill(q)}
		for _, p := range q.Producers {
			out = append(out, inv.scriptKills(p, open)...)
		}
		return out
	case "d1", "r2", "kv":
		var out []model.KillTarget
		for _, s := range inv.scriptsUsing(usage.ResourceKind(metric), resource) {
			out = append(out, inv.scriptKills(s, open)...)
		}
		return out
	}
	return nil
}

func dedupe(ts []model.KillTarget, max int) []model.KillTarget {
	seen := map[string]bool{}
	out := []model.KillTarget{}
	for _, t := range ts {
		id := killswitch.ID(t.Kind, t.Target)
		if seen[id] {
			continue
		}
		seen[id] = true
		out = append(out, t)
		if max > 0 && len(out) == max {
			break
		}
	}
	return out
}

func contains(xs []string, x string) bool {
	for _, y := range xs {
		if y == x {
			return true
		}
	}
	return false
}
