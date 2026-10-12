# CF Usage

![Go](https://img.shields.io/badge/Go-1.27-00ADD8?logo=go&logoColor=white)
![SQLite](https://img.shields.io/badge/SQLite-modernc-003B57?logo=sqlite&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=jetpackcompose&logoColor=white)
![Jetpack Glance](https://img.shields.io/badge/Jetpack_Glance-widgets-3DDC84?logo=android&logoColor=white)
![Cloudflare](https://img.shields.io/badge/Cloudflare-GraphQL_Analytics-F38020?logo=cloudflare&logoColor=white)
![Tailscale](https://img.shields.io/badge/Tailscale-serve-242424?logo=tailscale&logoColor=white)

CF Usage puts your Cloudflare account's usage on your Android home screen and warns you
before anything becomes billable. It tracks every metric the Workers Paid plan includes
(Workers, Durable Objects, D1, R2, KV and Queues), projects where the billing cycle will
end, alerts on thresholds, projections and sudden spikes, and can shut off a runaway
Worker from your phone.

A small Go server polls Cloudflare's GraphQL Analytics API once a minute and keeps the
history. The phone talks only to that server, over your Tailscale network, so no
Cloudflare token ever reaches the phone. Reading analytics doesn't count against your D1,
R2 or KV usage.

---

## Features

- **23 billable metrics** against the Workers Paid allowances, with the cycle to date, a
  projection to the end of the cycle and an estimated overage bill
- **Projections that ignore one-off bursts**: a one-day import isn't extrapolated over the
  rest of the cycle, and storage is billed the way Cloudflare bills it (a GB-month average)
- **Alerts** at 50 / 75 / 90 / 100%, on projected overage, on hourly spikes, and when the
  poller can't reach Cloudflare. Each alert fires once, survives restarts, and names the
  Worker or database responsible.
- **Push without Firebase**: the app keeps its own Server-Sent Events connection to the
  server from a foreground service. Updates are gzipped (about 300–400 bytes each), so a
  one-minute update rate uses about 30 MB a month.
- **Kill switches**: block a hostname at the WAF, turn off a Worker's `workers.dev` route,
  remove its cron triggers, or pause a queue. Each one is confirmed with your fingerprint
  or screen lock and can be undone, and WAF blocks are checked at the edge. The edit token
  stays on the server, and a dry-run mode changes nothing on Cloudflare.
- **Workers and sites**: per-Worker requests, errors and CPU time, and per-host traffic with
  paths, countries, data centres, status codes and blocked requests
- **Home-screen widgets** you design in the app from blocks (metric sections and rows,
  single metrics, top Workers, the estimated bill, status, and Overview-style product
  cards), in three sizes
- **Adjustable update rate**, with a separate rate on mobile data

## Tech stack

### Server

| Component | Role |
|---|---|
| **Go** standard library (`net/http`, `log/slog`) | Poller, REST API and the SSE event stream |
| **SQLite** ([modernc.org/sqlite](https://modernc.org/sqlite), pure Go) | Hourly usage per resource, alerts, kill switches, settings |
| **Cloudflare GraphQL Analytics** | Every usage metric, one query for the cycle and one for the last 48 hours |
| **Cloudflare REST API** | Inventory (names, domains, bindings, crons) and the kill switches |
| **Tailscale Serve** | TLS with a tailnet certificate, and the caller's login on every request |
| **systemd** | A sandboxed service that only needs outbound HTTPS and its own state directory |

### Android app

- **Kotlin** and **Jetpack Compose** (Material 3) for the app, **Jetpack Glance** for the widgets
- **OkHttp** with `okhttp-sse` for the API and the event stream, **kotlinx.serialization** for JSON
- A `specialUse` **foreground service** for the stream, **WorkManager** as a safety net,
  **DataStore** for settings, **AndroidX Biometric** for confirming kill switches

---

## Architecture

```
 ┌──────────── Android ────────────┐
 │  app + home-screen widgets      │
 │  foreground service (SSE)       │
 └───────────────┬─────────────────┘
                 │  HTTPS over Tailscale
                 │  /api/v1/*  and  /api/v1/events (gzipped SSE)
 ┌───────────────▼──── your server (Linux, or a Proxmox LXC) ─────┐
 │  tailscale serve :443 ──► 127.0.0.1:8787                       │
 │                                                                │
 │  cfusage (Go)                                                  │
 │    ├── poller      GraphQL every minute, backfill on start     │
 │    ├── SQLite      hourly history, alerts, kills, settings     │
 │    ├── alerts      thresholds, projection, spikes              │
 │    └── kill switches  WAF block, workers.dev, crons, queues    │
 └───────────────┬────────────────────────────────────────────────┘
                 │  outbound HTTPS only
 ┌───────────────▼────────────────────┐
 │  Cloudflare API                    │
 │  GraphQL Analytics + REST v4       │
 └────────────────────────────────────┘
```

**Polling.** Each minute the server makes two GraphQL calls: cycle-to-date totals for every
metric, and the last 48 hours by hour (Cloudflare fills in minutes late, so recent hours are
re-read every time). On start it backfills the whole cycle. The inventory of names,
domains, queues and bindings is refreshed every 30 minutes. All of this stays far below
the GraphQL limit of 300 queries per 5 minutes.

**Push.** The app holds one SSE stream. The server sends a new summary after each poll,
throttled to the update rate each phone asks for (`?every=`). Alerts and kill-switch
results are sent at once. The stream shares one gzip compressor for its whole life, so
an update costs only what changed since the last one.

**Who can change things.** There is no app-level login. Only tailnet devices can reach the
server, and `tailscale serve` adds the caller's `Tailscale-User-Login` to each request.
Acknowledging alerts, changing settings and applying kill switches need a login listed in
`allowed_logins`.

The full contract between the two sides is in [`docs/API.md`](docs/API.md).

---

## Project structure

```
server/
  cmd/cfusage/          entry point and config file
  internal/cf/          Cloudflare client: GraphQL and REST, tokens read from files per call
  internal/usage/       metric table, GraphQL queries, billing cycle, counting rules
  internal/monitor/     poller, projections, alerts, inventory, detail views
  internal/killswitch/  apply, verify and restore kill switches
  internal/store/       SQLite schema and queries
  internal/api/         REST API, SSE stream, gzip, tailnet login check
android/                Android app (Kotlin, Compose, Glance)
  app/src/main/kotlin/io/github/pablocollantes/cfusage/
    data/                 API client, models, local stores, update rate
    push/                 foreground SSE service, notifications, boot receiver, safety net
    ui/                   screens, charts, components, theme
    widget/               widget designs, layouts and the designer
docs/
  API.md                the API both sides build against
  fixtures/             anonymised server responses, parsed by the app's tests
deploy/
  cfusage.service       hardened systemd unit
  config.example.json   server config
  proxmox/              create a Debian LXC, then build and install into it
```

---

## Quick start

### Prerequisites

- A Cloudflare account. The allowances assume the Workers Paid plan.
- Go 1.27+
- JDK 17 and the Android SDK (API 36), and a phone on Android 10 or later
- [Tailscale](https://tailscale.com) on the server and the phone

### 1. Create two API tokens

In the Cloudflare dashboard, open **My Profile → API Tokens → Create Token**:

- **Read token** (metrics and names). Account: Account Analytics Read, Workers Scripts
  Read, D1 Read, Queues Read, Workers KV Storage Read. Zone: Zone Read, Analytics Read.
- **Kill-switch token** (only needed for kill switches). Account: Workers Scripts Edit,
  Queues Edit. Zone: Zone WAF Edit.

Both tokens stay on the server.

### 2. Run the server locally

```bash
cd server
mkdir -p dev                       # git-ignored
# put the read token in dev/read_token (and the kill-switch token in dev/kill_token)
cat > dev/config.json <<'EOF'
{
  "read_token_file": "dev/read_token",
  "kill_token_file": "dev/kill_token",
  "db": "dev/cfusage.db",
  "cycle_day": 1,
  "dry_run": true
}
EOF
go run ./cmd/cfusage -config dev/config.json      # http://127.0.0.1:8787
```

```bash
curl -s http://127.0.0.1:8787/api/v1/health
curl -s http://127.0.0.1:8787/api/v1/summary
```

The account is detected from the token when it can see only one account. Otherwise set
`account_id`. Set `cycle_day` to the day your Cloudflare billing cycle starts.

### 3. Deploy it

On any Linux host that's on your tailnet:

```bash
cd server && ./build.sh            # vet, race tests, static bin/cfusage-linux-amd64

sudo useradd --system --home /var/lib/cfusage --shell /usr/sbin/nologin cfusage
sudo install -m 0755 bin/cfusage-linux-amd64 /usr/local/bin/cfusage
sudo install -d -m 0750 -o root -g cfusage /etc/cfusage
sudo install -m 0640 -o root -g cfusage ../deploy/config.example.json /etc/cfusage/config.json
for t in read_token kill_token; do
  sudo install -m 0640 -o root -g cfusage /dev/null /etc/cfusage/$t
  sudo tee /etc/cfusage/$t >/dev/null          # paste the token, then Ctrl-D
done
sudo install -m 0644 ../deploy/cfusage.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now cfusage

sudo tailscale serve --bg 8787     # https://<host>.<your-tailnet>.ts.net
```

Then edit `/etc/cfusage/config.json`: set `allowed_logins` to your Tailscale login and
`cycle_day` to your billing day. Kill switches start in dry-run mode, so set `dry_run` to
`false` when you want them to act. Restart with `sudo systemctl restart cfusage`.

**On Proxmox**, two scripts do the same in a small unprivileged Debian container:

```bash
ssh root@<proxmox-host> 'bash -s' < deploy/proxmox/create-lxc.sh     # prints CTID=<id>
ssh root@<proxmox-host> pct exec <id> -- tailscale up --hostname cf-usage
ssh root@<proxmox-host> pct exec <id> -- tailscale serve --bg 8787

PVE=root@<proxmox-host> READ_TOKEN_FILE=<file> KILL_TOKEN_FILE=<file> \
  deploy/proxmox/install.sh --tokens            # later upgrades: without --tokens
```

### 4. Install the app

```bash
cd android
echo "cfusage.serverUrl=https://cf-usage.<your-tailnet>.ts.net" >> local.properties
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The URL can also be changed later under **Settings** in the app. Open the app once to start
the connection and allow notifications. On Samsung and other phones that pause background
work, also exempt it from battery optimisation (the app asks, or run
`adb shell cmd deviceidle whitelist +io.github.pablocollantes.cfusage`).

To keep app data when you reinstall from another machine, you can create a project-local
debug key. The build uses it automatically when it exists, and it is git-ignored:

```bash
keytool -genkey -v -keystore android/debug.keystore -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US" -storepass android -keypass android
```

---

## Commands

| Command | Description |
|---|---|
| `server/build.sh` | `go vet`, race tests, and a static `linux/amd64` binary in `server/bin/` |
| `go test -race ./...` (in `server/`) | Server tests |
| `./gradlew testDebugUnitTest` (in `android/`) | App unit tests, including parsing every file in `docs/fixtures` |
| `./gradlew assembleDebug` (in `android/`) | Debug APK |
| `deploy/proxmox/create-lxc.sh` | Create the Debian container on a Proxmox host |
| `deploy/proxmox/install.sh [--tokens]` | Build, then install or upgrade the server in the container |

## License

MIT, see [LICENSE](LICENSE).
