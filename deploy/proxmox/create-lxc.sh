#!/usr/bin/env bash
# Creates the cf-usage LXC on the Proxmox host. Run ON the host as root:
#   ssh root@proxmox 'bash -s' < deploy/proxmox/create-lxc.sh
# Idempotent: if a container named cf-usage exists it only makes sure it is running.
#
# Unprivileged Debian container, 512 MB, 1 core, 4 GB disk. Tailscale inside it needs
# /dev/net/tun, so the device is passed through; without it tailscaled falls back to
# userspace networking, which `tailscale serve` handles too but with more overhead.
set -euo pipefail

NAME=cf-usage
STORAGE=${STORAGE:-local-lvm}
BRIDGE=${BRIDGE:-vmbr0}

existing=$(pct list | awk -v n="$NAME" '$NF == n {print $1}')
if [ -n "$existing" ]; then
  echo "container $NAME already exists as $existing"
  pct status "$existing" | grep -q running || pct start "$existing"
  echo "CTID=$existing"
  exit 0
fi

pveam update >/dev/null
# Debian 12: PVE 8.4's container tooling predates Debian 13.
tmpl=$(pveam available --section system | awk '{print $2}' | grep -E '^debian-12-standard_.*amd64' | sort -V | tail -1)
if ! pveam list local | grep -q "$tmpl"; then
  pveam download local "$tmpl"
fi

ctid=$(pvesh get /cluster/nextid)
pct create "$ctid" "local:vztmpl/$tmpl" \
  --hostname "$NAME" \
  --memory 512 --swap 256 --cores 1 \
  --rootfs "$STORAGE:4" --ostype debian \
  --net0 "name=eth0,bridge=$BRIDGE,firewall=1,ip=dhcp" \
  --unprivileged 1 --features nesting=1 \
  --onboot 1 --description "Cloudflare usage monitor. Go poller + SQLite, served over tailscale serve."

cat >> "/etc/pve/lxc/$ctid.conf" <<'EOF'
lxc.cgroup2.devices.allow: c 10:200 rwm
lxc.mount.entry: /dev/net/tun dev/net/tun none bind,create=file
EOF

pct start "$ctid"
# Wait for DHCP and DNS before installing anything.
for _ in $(seq 30); do
  pct exec "$ctid" -- getent hosts deb.debian.org >/dev/null 2>&1 && break
  sleep 2
done
pct exec "$ctid" -- bash -c 'apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl ca-certificates sqlite3 >/dev/null'
pct exec "$ctid" -- bash -c 'curl -fsSL https://tailscale.com/install.sh | sh >/dev/null'
pct exec "$ctid" -- bash -c 'id cfusage >/dev/null 2>&1 || useradd --system --home /var/lib/cfusage --shell /usr/sbin/nologin cfusage'
pct exec "$ctid" -- install -d -m 0750 -o root -g cfusage /etc/cfusage
echo "CTID=$ctid"
