#!/usr/bin/env bash
# VPS wrapper: base stack only (no dev override) + production .env.
# Usage: scripts/compose-vps.sh up -d --wait | down | logs postgres | ...
set -euo pipefail
cd "$(dirname "$0")/.."
exec docker compose -f infra/compose/compose.yaml --env-file infra/compose/.env "$@"
