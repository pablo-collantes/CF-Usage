#!/usr/bin/env bash
# Builds the server and installs or upgrades it in the cf-usage LXC. Run from the repo:
#   PVE=root@<proxmox-host> deploy/proxmox/install.sh [--tokens]
# --tokens also copies the two Cloudflare tokens, from the files named by READ_TOKEN_FILE
# and KILL_TOKEN_FILE. They travel over ssh stdin into 0640 root:cfusage files and are
# never put on a command line or printed.
set -euo pipefail
cd "$(dirname "$0")/../.."

: "${PVE:?set PVE to the Proxmox host, e.g. PVE=root@pve.local}"
SSH=(ssh -o ControlMaster=auto -o ControlPath="$HOME/.ssh/cm-%r@%h:%p" -o ControlPersist=60 "$PVE")
CTID=$("${SSH[@]}" "pct list | awk '\$NF == \"cf-usage\" {print \$1}'")
[ -n "$CTID" ] || { echo "no cf-usage container; run create-lxc.sh first" >&2; exit 1; }
in_ct() { "${SSH[@]}" pct exec "$CTID" -- "$@"; }
# pct push reads a host path, so stream through a temp file on the host.
push() { # push <local file> <path in container> <mode> <owner:group>
  local tmp; tmp=$("${SSH[@]}" mktemp)
  "${SSH[@]}" "cat > $tmp" < "$1"
  "${SSH[@]}" "pct push $CTID $tmp $2 --perms $3 --user ${4%%:*} --group ${4##*:}; rc=\$?; rm -f $tmp; exit \$rc"
}

if [ "${1:-}" = "--tokens" ]; then
  : "${READ_TOKEN_FILE:?set READ_TOKEN_FILE to the file holding the analytics read token}"
  : "${KILL_TOKEN_FILE:?set KILL_TOKEN_FILE to the file holding the kill-switch token}"
fi

(cd server && ./build.sh)
# The running binary can't be overwritten ("Text file busy"), but it can be renamed over.
push server/bin/cfusage-linux-amd64 /usr/local/bin/cfusage.new 0755 root:root
in_ct mv -f /usr/local/bin/cfusage.new /usr/local/bin/cfusage
push deploy/cfusage.service /etc/systemd/system/cfusage.service 0644 root:root
if ! in_ct test -f /etc/cfusage/config.json; then
  push deploy/config.example.json /etc/cfusage/config.json 0640 root:cfusage
  echo "installed deploy/config.example.json as /etc/cfusage/config.json; edit allowed_logins there"
fi
if [ "${1:-}" = "--tokens" ]; then
  push "$READ_TOKEN_FILE" /etc/cfusage/read_token 0640 root:cfusage
  push "$KILL_TOKEN_FILE" /etc/cfusage/kill_token 0640 root:cfusage
fi
in_ct systemctl daemon-reload
in_ct systemctl enable cfusage
in_ct systemctl restart cfusage
sleep 3
in_ct systemctl --no-pager --lines=5 status cfusage
in_ct curl -fsS http://127.0.0.1:8787/api/v1/health
echo
