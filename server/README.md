# cfusage server

The poller and API behind the Android app. It reads Cloudflare usage from the
GraphQL Analytics API once a minute, keeps hourly history in SQLite, fires
alerts, applies kill switches, and serves `docs/API.md` on `127.0.0.1:8787` for
`tailscale serve` to publish on the tailnet.

Reading analytics costs no D1, R2 or KV usage, which is why the monitor reads
analytics rather than the resources themselves.

```
./build.sh                       # vet, race tests, static bin/cfusage-linux-amd64
go run ./cmd/cfusage -config c.json
```

Install it with `deploy/cfusage.service` and a config like
`deploy/config.example.json`. Tokens go in `/etc/cfusage/read_token` and
`/etc/cfusage/kill_token` (`root:cfusage 0640`). The server reads them from those
files on every call, so it never logs or returns them, and rotating one needs no
restart.

## What a poll does

Each minute it makes two GraphQL calls:

- **Cycle-to-date totals** for every billable metric, plus the daily storage
  gauges and how far behind each dataset is. One query covers the whole cycle,
  because account datasets allow 32-day windows and data up to 90 days back.
- **The last 48 hours by hour**, which replaces those hours in `usage_hourly`.
  Cloudflare fills in minutes late, so recent hours are re-read every time.

Other calls:

- **Startup:** every day of the cycle, and the last 8 days, is backfilled by
  hour, one call per day. A day counts as final once it has been read 2 hours
  after it ended.
- **Every 5 minutes:** the 24 h sites list, which is cached.
- **Every 30 minutes:** the inventory. That means names, custom domains,
  queues, and script bindings, workers.dev settings and cron schedules.

Detail views (sites, colos) are queried on demand and cached for 60 s. All of
this stays far under the 300-queries-per-5-minutes GraphQL limit.

Totals, projections, R2 operation classes, DO WebSocket messages (20:1) and
storage GB-months follow the counting rules in `docs/API.md`. The totals were
checked against independent GraphQL queries and differ by under 0.03%, the gap
being data that arrived in between.

## Things worth knowing

- **Projection** is used plus hours left × the faster of the cycle-average and
  last-24 h rates. It only affects status and alerts once 10% of the cycle has
  passed.
- **Alerts fire once.** The alert id encodes what it is about, such as
  `threshold:<metric>:<pct>:<cycle start>` or `spike:<metric>:<hour>`. The
  store keeps each id once, so restarts don't re-send alerts.
- **Kill switches are limited.** Only targets listed by `GET /api/v1/kill` can
  be applied. Hosts are validated before they go into the WAF expression. Every
  `waf_block` shares one custom rule, `cfusage kill switch`, which is created at
  the top of the zone's rules, edited with PATCH and deleted when empty. The
  entrypoint is never replaced. With `"dry_run": true` nothing reaches
  Cloudflare.
- **Changes need an allowed tailnet login.** `POST`, `PUT` and `DELETE` require
  a `Tailscale-User-Login` header in `allowed_logins`. A request proxied by
  `tailscale serve` without that header, such as one from a tagged device, is
  refused. A request with no proxy headers is allowed only from loopback, for
  local testing.
- **Logs:** `log_level: debug` logs every GraphQL query with its duration.
  Polls normally take 1.5–2 s, and Cloudflare occasionally takes up to 20 s.
