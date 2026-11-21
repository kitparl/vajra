#!/usr/bin/env bash
# Local dev wrapper: base stack + dev override + local .env.
# Usage: scripts/compose.sh up -d --wait | down | logs postgres | ...
set -euo pipefail
cd "$(dirname "$0")/.."
exec docker compose -f infra/compose/compose.yaml -f infra/compose/compose.dev.yaml --env-file infra/compose/.env "$@"
