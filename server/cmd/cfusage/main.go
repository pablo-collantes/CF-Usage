// Command cfusage is the Cloudflare usage monitor's server: it polls GraphQL
// Analytics, keeps history in SQLite, alerts, applies kill switches and serves
// the app's API on the tailnet (see docs/API.md).
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/pablo-collantes/cfusage/server/internal/api"
	"github.com/pablo-collantes/cfusage/server/internal/cf"
	"github.com/pablo-collantes/cfusage/server/internal/killswitch"
	"github.com/pablo-collantes/cfusage/server/internal/monitor"
	"github.com/pablo-collantes/cfusage/server/internal/store"
)

type Config struct {
	AccountID     string   `json:"account_id"`
	ZoneIDs       []string `json:"zone_ids"` // empty: every zone the read token sees
	ReadTokenFile string   `json:"read_token_file"`
	KillTokenFile string   `json:"kill_token_file"`
	DB            string   `json:"db"`
	Listen        string   `json:"listen"`
	AllowedLogins []string `json:"allowed_logins"`
	CycleDay      int      `json:"cycle_day"` // default until changed through PUT /api/v1/settings
	PollSeconds   int      `json:"poll_seconds"`
	DryRun        bool     `json:"dry_run"` // kill switches change nothing on Cloudflare
	LogLevel      string   `json:"log_level"`
}

func loadConfig(path string) (Config, error) {
	c := Config{
		ReadTokenFile: "/etc/cfusage/read_token",
		KillTokenFile: "/etc/cfusage/kill_token",
		DB:            "/var/lib/cfusage/cfusage.db",
		Listen:        "127.0.0.1:8787",
		CycleDay:      1,
		PollSeconds:   60,
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return c, err
	}
	d := json.NewDecoder(bytes.NewReader(b))
	d.DisallowUnknownFields()
	if err := d.Decode(&c); err != nil {
		return c, fmt.Errorf("%s: %w", path, err)
	}
	if c.CycleDay < 1 || c.CycleDay > 28 {
		return c, errors.New("cycle_day must be 1-28")
	}
	if c.PollSeconds < 30 {
		return c, errors.New("poll_seconds must be at least 30")
	}
	return c, nil
}

func main() {
	cfgPath := flag.String("config", "/etc/cfusage/config.json", "config file")
	version := flag.Bool("version", false, "print the version and exit")
	flag.Parse()
	if *version {
		fmt.Println(monitor.Version)
		return
	}
	cfg, err := loadConfig(*cfgPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "config:", err)
		os.Exit(2)
	}
	level := slog.LevelInfo
	_ = level.UnmarshalText([]byte(cfg.LogLevel))
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level}))
	if err := run(cfg, log); err != nil {
		log.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run(cfg Config, log *slog.Logger) error {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	read := cf.New(cfg.ReadTokenFile)
	if cfg.AccountID == "" {
		var accts []struct{ ID string }
		if err := read.REST(ctx, http.MethodGet, "accounts", nil, &accts); err != nil {
			return fmt.Errorf("finding the account: %w", err)
		}
		if len(accts) != 1 {
			return fmt.Errorf("the read token sees %d accounts; set account_id", len(accts))
		}
		cfg.AccountID = accts[0].ID
	}
	if err := os.MkdirAll(filepath.Dir(cfg.DB), 0o750); err != nil {
		return err
	}
	st, err := store.Open(cfg.DB)
	if err != nil {
		return err
	}
	defer st.Close()

	hub := api.NewHub()
	hub.Log = log
	mon := monitor.New(monitor.Config{Account: cfg.AccountID, ZoneIDs: cfg.ZoneIDs,
		PollEvery: time.Duration(cfg.PollSeconds) * time.Second, DefaultCycleDay: cfg.CycleDay},
		read, st, log, hub.Publish)
	kill := &killswitch.Service{
		CF: cf.New(cfg.KillTokenFile), Store: st, Account: cfg.AccountID, Zones: mon.Zones,
		DryRun: cfg.DryRun, Emit: mon.OnKill, Log: log, Ctx: ctx,
	}
	mon.Kill = kill
	if err := mon.Init(ctx); err != nil {
		return err
	}
	if cfg.DryRun {
		log.Warn("dry run: kill switches will not change anything on Cloudflare")
	} else if _, err := os.Stat(cfg.KillTokenFile); err != nil {
		log.Warn("no kill token; kill switches will fail", "err", err)
	}

	a := &api.API{Mon: mon, Kill: kill, Store: st, Hub: hub, Log: log, AllowedLogins: cfg.AllowedLogins}
	srv := &http.Server{Addr: cfg.Listen, Handler: a.Handler(), ReadHeaderTimeout: 10 * time.Second,
		BaseContext: func(_ net.Listener) context.Context { return ctx }}
	errc := make(chan error, 1)
	go func() {
		log.Info("listening", "addr", cfg.Listen, "account", cfg.AccountID, "dryRun", cfg.DryRun)
		errc <- srv.ListenAndServe()
	}()
	go mon.Run(ctx)

	select {
	case <-ctx.Done():
	case err := <-errc:
		return err
	}
	log.Info("shutting down")
	sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(sctx)
	kill.Wait()
	return nil
}
