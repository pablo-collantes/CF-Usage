#!/usr/bin/env bash
# Builds the static linux/amd64 binary for the server host: bin/cfusage-linux-amd64.
# Pure Go (modernc.org/sqlite), so CGO is off and the binary has no libc dependency.
set -euo pipefail
cd "$(dirname "$0")"
version=$(git describe --tags --always --dirty 2>/dev/null || echo dev)
go vet ./...
go test -race -count=1 ./...
mkdir -p bin
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath \
  -ldflags "-s -w -X github.com/pablo-collantes/cfusage/server/internal/monitor.Version=0.1.0-$version" \
  -o bin/cfusage-linux-amd64 ./cmd/cfusage
ls -l bin/cfusage-linux-amd64
