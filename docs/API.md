# cfusage server API (v1)

The contract between the Go poller (`server/`) and the Android app (`android/`).
Both sides build against this file. Change it here first, then in both sides.

- Base URL: `https://cf-usage.<your-tailnet>.ts.net` (the host's `tailscale serve`, which
  terminates TLS with a real tailnet certificate and proxies to `127.0.0.1:8787`).
- No app-level auth. Only tailnet devices can reach the server. `tailscale serve` adds
  `Tailscale-User-Login` to every proxied request; mutating endpoints (`POST`, `PUT`,
  `DELETE`) require that header to be in the server's `allowed_logins` list
  (`allowed_logins` in the config file). Requests that arrive without the header (straight to
  `127.0.0.1:8787`) are allowed only from loopback, for local testing.
- JSON everywhere, `camelCase` keys, times as RFC 3339 UTC strings (`2026-10-06T21:39:00Z`),
  dates as `YYYY-MM-DD`. Numbers are JSON numbers (float64). Absent optional fields
  are `null`, never omitted.
- Responses are gzip-compressed when the request sends `Accept-Encoding: gzip`.
- Summary numbers are rounded to what a screen shows, so they don't change on every poll
  for no reason: `pct`/`projectedPct` and `cycle.elapsed` to 4 decimals; `projected`,
  `rate24h` and `overage.units` to 4 significant digits; dollars to cents; `used`, `top`
  and `daily` values to 3 decimals. Alerts are judged on the unrounded numbers.
- Errors: non-2xx with `{"error": "human readable message"}`.

## Billing cycle

The cycle starts on day `cycleDay` (default **1**; set it to your billing day) at 00:00 UTC and ends on the same
day next month. With `cycleDay` 23, cycle 2026-09-23 → 2026-10-23 is current on 2026-10-06.

## Types

```ts
type Status = "ok" | "info" | "warn" | "critical" | "over"
// ok < 50% · info ≥ 50% · warn ≥ 75% or projected ≥ 100% · critical ≥ 90% · over ≥ 100%
// (thresholds come from settings)

type Metric = {
  key: string            // "workers_requests", see the metric table below
  label: string          // "Workers requests"
  group: string          // "Workers" | "Durable Objects" | "D1" | "R2" | "KV" | "Queues"
  unit: "count" | "ms" | "gbs" | "gb"      // gb = GB-month for storage
  kind: "flow" | "storage"
  used: number           // cycle to date. Storage: current size (GB), as the dashboard shows it
  included: number       // Workers Paid allowance per cycle
  pct: number            // used / included (0.42 = 42%)
  projected: number      // end-of-cycle estimate, see "Projection". Storage: the GB-months the cycle bills
  projectedPct: number
  rate24h: number        // units used in the last 24 h (flow); current size (storage)
  status: Status
  overage: { units: number, usd: number }  // projected units above included, and their cost
  price: string          // "$0.30 / million", from the pricing page
  sampled: boolean       // true when the dataset is sampled (counts are estimates)
  top: { name: string, value: number }[]   // biggest contributors this cycle, max 5
  daily: { date: string, value: number }[] // flow: amount used that UTC day; storage: that day's peak, in unit
}

type Summary = {
  generatedAt: string
  lastPollAt: string | null      // last successful poll
  pollError: string | null       // last poll's error, null when the last poll succeeded
  dataAsOf: string | null        // newest minute Cloudflare had data for (the oldest of the key datasets)
  cycle: { start: string, end: string, cycleDay: number, elapsed: number, daysLeft: number }
  status: Status                 // worst metric status
  estimatedBillUsd: number       // $5 base + every metric's projected overage
  metrics: Metric[]              // fixed order, the metric table below
  alerts: Alert[]                // unacknowledged, newest first
  kills: Kill[]                  // kill switches currently applied (state != "restored")
}

type Alert = {
  id: string                     // stable: "threshold:workers_requests:90:2026-09-23"
  kind: "threshold" | "projection" | "spike" | "monitor"
  severity: "info" | "warn" | "critical"
  metric: string | null          // metric key
  title: string                  // "Workers requests at 90%"
  body: string                   // "9.0M of 10M used, 16 days left. mailbox made 61%."
  createdAt: string
  ackedAt: string | null
  suggested: KillTarget[]        // what to shut off, biggest contributor first
}

type KillTarget = {
  kind: "waf_block" | "workers_dev_off" | "cron_off" | "queue_pause"
  target: string                 // hostname, script name or queue id
  label: string                  // "Block mailbox.example.com"
  description: string            // what it stops and what it doesn't
}

type Kill = KillTarget & {
  id: string                     // "waf_block:mail.example.com"
  state: "applying" | "active" | "restoring" | "restored" | "failed"
  appliedAt: string
  verifiedAt: string | null      // when the server saw the block take effect
  restoredAt: string | null
  reason: string | null
  error: string | null
}
```

## Metric table

Keys, labels and Workers Paid allowances (from Cloudflare's pricing pages for each
product). Order is fixed and is the order the app shows them in.

| key | label | unit | included | overage price |
|---|---|---|---|---|
| workers_requests | Workers requests | count | 10M | $0.30 / M |
| workers_cpu_ms | Workers CPU time | ms | 30M | $0.02 / M ms |
| do_requests | DO requests | count | 1M | $0.15 / M |
| do_duration_gbs | DO duration | gbs | 400k | $12.50 / M GB-s |
| do_sql_rows_read | DO SQLite rows read | count | 25B | $0.001 / M |
| do_sql_rows_written | DO SQLite rows written | count | 50M | $1.00 / M |
| do_sql_storage_gb | DO SQLite storage | gb | 5 | $0.20 / GB-mo |
| do_kv_read_units | DO KV read units | count | 1M | $0.20 / M |
| do_kv_write_units | DO KV write units | count | 1M | $1.00 / M |
| do_kv_deletes | DO KV deletes | count | 1M | $1.00 / M |
| do_kv_storage_gb | DO KV storage | gb | 1 | $0.20 / GB-mo |
| d1_rows_read | D1 rows read | count | 25B | $0.001 / M |
| d1_rows_written | D1 rows written | count | 50M | $1.00 / M |
| d1_storage_gb | D1 storage | gb | 5 | $0.75 / GB-mo |
| r2_class_a | R2 Class A ops | count | 1M | $4.50 / M |
| r2_class_b | R2 Class B ops | count | 10M | $0.36 / M |
| r2_storage_gb | R2 storage | gb | 10 | $0.015 / GB-mo |
| kv_reads | KV reads | count | 10M | $0.50 / M |
| kv_writes | KV writes | count | 1M | $5.00 / M |
| kv_deletes | KV deletes | count | 1M | $5.00 / M |
| kv_lists | KV lists | count | 1M | $5.00 / M |
| kv_storage_gb | KV storage | gb | 1 | $0.50 / GB-mo |
| queues_ops | Queues operations | count | 1M | $0.40 / M |

The app should present the most important four on the home screen and in the
widgets by default: `workers_requests`, `workers_cpu_ms`, `do_requests`, `d1_rows_read`
(user-configurable later), plus whichever metric has the worst status.

### Projection

- Flow metrics: `projected = used + rate × hoursLeft`, where `rate` is the larger of the
  cycle-average hourly rate and the median of the last three complete UTC days (÷ 24).
  The median keeps a one-day burst (an import, a backfill) from being extrapolated over the
  rest of the cycle; a change that lasts two days or more still lifts it. Sudden jumps are
  the hourly spike alert's job. With fewer than three complete days only the cycle average
  is used.
- Storage metrics: `projected = (sum of daily peaks so far + latest size × days left) / days in cycle`.
  Cloudflare bills storage as the average of each day's peak over the billing period, so this
  is the billed GB-month figure. It can be below `used` early in a cycle when storage grew
  recently (for example 4.92 GB now but 3.63 GB-month billed, for a bucket that was
  0.1 GB for the cycle's first 8 days). Status and thresholds use `used` (the size now);
  overage uses `projected`.
- Storage gaps: Cloudflare's daily gauges skip days now and then. A resource keeps its last
  size through a gap; one not reported for more than a day before the newest date counts as
  deleted and leaves `used`.
- R2 storage and operations: the free tier covers Standard only. Infrequent Access storage is
  not counted.

### Counting rules

- `do_requests`: HTTP, RPC and alarm invocations count 1 each. Incoming WebSocket messages
  count 1/20 (DO pricing, example 4): invocations of type `hibernation` (messages to a
  hibernated object), plus `inboundWebsocketMsgCount` for namespaces with no such invocations.
- `workers_requests`: `workersInvocationsAdaptive` plus Pages Functions invocations not
  already listed there. Static asset requests are free and not counted.

## Endpoints

### `GET /api/v1/health`
`{"ok": true, "version": "0.1.0", "lastPollAt": "...", "pollError": null}`. Always 200
while the process is up.

### `GET /api/v1/summary` → `Summary`
Served from memory, rebuilt after every poll (about once a minute).

### `GET /api/v1/metrics/{key}?range=24h|7d|cycle` → `MetricDetail`
```ts
type MetricDetail = {
  metric: Metric
  range: "24h" | "7d" | "cycle"
  bucket: "hour" | "day"            // hour for 24h and 7d, day for cycle
  series: { t: string, value: number }[]          // amount in each bucket, oldest first, gaps filled with 0
  cumulative: { t: string, value: number }[]      // running cycle total at the end of each bucket (cycle range only, else [])
  byResource: { name: string, value: number, share: number }[]   // every contributor in the range, largest first
}
```

### `GET /api/v1/workers?range=24h|7d|cycle` → `WorkerRow[]`
Every Worker script and Pages Functions project with traffic in the range.
```ts
type WorkerRow = {
  name: string                  // script name; Pages Functions as "pages:<scriptName>"
  kind: "worker" | "pages"
  requests: number
  errors: number
  errorRate: number             // errors / requests
  cpuMs: number                 // total CPU ms (0 for pages)
  cpuMsPerRequest: number
  subrequests: number
  shareOfRequests: number       // of the account's workers_requests in the range
  hosts: string[]               // custom domains that route to it, from the inventory
  spark: number[]               // requests per bucket across the range (24 points for 24h, 7×24 for 7d → downsample to 28, one per day for cycle)
  killable: KillTarget[]        // the kill switches that apply to this script
}
```

### `GET /api/v1/workers/{name}?range=24h|7d|cycle` → `WorkerDetail`
```ts
type WorkerDetail = {
  row: WorkerRow
  bucket: "hour" | "day"
  series: { t: string, requests: number, errors: number, cpuMs: number }[]
  colos: { colo: string, requests: number }[]     // top 15 Cloudflare data centres
  site: SiteDetail | null       // traffic detail of its first custom domain on a zone, if any
}
```

### `GET /api/v1/sites?range=1h|24h|7d|30d` → `SiteRow[]`
Every hostname on the account's zones with visitor traffic (`requestSource: "eyeball"`)
in the range, from `httpRequestsAdaptiveGroups`. Static and cached hits are listed too:
they are free, but they show what is being hit.
```ts
type SiteRow = {
  host: string
  zone: string                  // "example.com"
  requests: number
  bytes: number                 // edgeResponseBytes
  blocked: number               // securityAction = block
  cachedShare: number           // requests with cacheStatus hit / total
  status4xx: number             // share of requests
  status5xx: number
  service: string | null        // "worker:mailbox" | "pages:<project>" | null
  spark: number[]               // requests per bucket, 24 points
  killable: KillTarget[]
}
```

### `GET /api/v1/sites/{host}?range=1h|24h|7d|30d` → `SiteDetail`
```ts
type SiteDetail = {
  row: SiteRow
  bucket: "minute5" | "hour" | "day"   // 5 min for 1h, hour for 24h and 7d, day for 30d
  series: { t: string, requests: number, blocked: number }[]
  paths: { path: string, requests: number }[]           // top 50
  countries: { country: string, requests: number }[]    // top 30; ISO 3166 alpha-2 code
  statuses: { status: number, requests: number }[]
  securityActions: { action: string, requests: number }[]
  userAgents: { agent: string, requests: number }[]     // top 15 browser families (userAgentBrowser)
  cache: { status: string, requests: number }[]
}
```
Cached for 60 s per (host, range).

### `GET /api/v1/alerts?limit=50` → `Alert[]`
History, newest first, acknowledged ones included.

### `POST /api/v1/alerts/{id}/ack` → `Alert`

### `GET /api/v1/kill` → `{ available: KillTarget[], kills: Kill[] }`
`available` lists every kill switch that can be applied right now; `kills` is the
full history including restored ones, newest first, max 50.

### `POST /api/v1/kill` body `{ kind, target, reason }` → `Kill` (202)
Applies a kill switch. Returns at once with `state: "applying"`; the result arrives on
the event stream. Re-applying an active kill returns the existing one (200).

### `DELETE /api/v1/kill/{id}` → `Kill` (202)
Restores what the kill changed, from the state saved when it was applied.

What each kind does (all through `cloudflare_kill_switch_token`, which stays on the server):

| kind | target | applies | restores |
|---|---|---|---|
| `waf_block` | hostname on a zone | adds the host to one managed custom rule, `cfusage kill switch`, action `block` (one rule for every blocked host, because the Free plan allows only 5 custom rules). Takes 6–20 s to reach every edge; the server verifies by requesting the host and seeing a Cloudflare 403. | removes the host, deletes the rule when no host is left |
| `workers_dev_off` | script | turns the script's `workers.dev` route and preview URLs off | the saved previous setting |
| `cron_off` | script | removes the script's cron triggers | the saved schedules |
| `queue_pause` | queue id | pauses delivery to consumers (messages wait in the queue) | resumes delivery |

D1, R2 and KV have no off switch of their own. The app offers the kills of the scripts
that bind the database or bucket (from the inventory's bindings), so "kill this database"
means blocking what calls it.

### `GET /api/v1/settings` → `Settings`, `PUT /api/v1/settings` body `Settings` → `Settings`
```ts
type Settings = {
  cycleDay: number              // 1-28, default 1
  thresholds: { info: number, warn: number, critical: number }  // 0.5, 0.75, 0.9
  projectionAlert: boolean      // alert when projected ≥ 100%, default true
  spikeFactor: number           // last hour ≥ factor × trailing 24 h hourly average, default 5
  spikeMinShare: number         // and the last hour alone ≥ this share of included, default 0.01
  muted: string[]               // metric keys that never alert
}
```

### `GET /api/v1/events?every=<seconds>` — Server-Sent Events
`Content-Type: text/event-stream`. Events:

- `event: summary` — `data:` the full `Summary` JSON (one line). Sent at once on connect.
  After that, a poll's summary goes out if `every` seconds have passed since the last one
  sent, give or take 30 s for poll timing (default and minimum 60, maximum 3600; the
  server still polls every minute, so `every` is effectively rounded to whole minutes).
  The connect summary doesn't start the interval, and neither does anything before an
  alert or kill: the summary right after one always goes out at once. Summaries in
  between are held, never queued: the newest held one goes out 30 s after the interval
  ends if no newer one arrives (only when polls stop).
- `event: alert` — `data:` an `Alert`, when one fires. Always sent at once, followed by
  that poll's summary, so the phone's widgets match the alert. The phone posts a notification
  for it (high-importance channel for `warn`/`critical`).
- `event: kill` — `data:` a `Kill`, on every state change. Sent at once like alerts.
- `: ping` comment after 70 s with nothing else sent. Clients treat 90 s of silence as
  a dead connection.

With `Accept-Encoding: gzip` (OkHttp sends it) the stream is gzip-compressed with one
compressor for the whole stream, flushed after every event. Each summary mostly repeats
the previous one, so after the first (about 3.5 KB) they cost about 350 bytes instead of 22 KB.

The server ends a stream after 2 hours; clients reconnect with backoff (1 s, 2 s, …
max 60 s). `Last-Event-ID` is not needed: every connect starts with a fresh summary,
and missed alerts are in `summary.alerts`.
