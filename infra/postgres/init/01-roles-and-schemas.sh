#!/usr/bin/env bash
# One login role + one schema per service (0003). Idempotent: safe to re-run.
# Runs automatically on first start of an empty data volume.
set -euo pipefail

declare -A PASSWORDS=(
  [core]="${CORE_DB_PASSWORD:-}"
  [risk]="${RISK_DB_PASSWORD:-}"
  [gateway]="${GATEWAY_DB_PASSWORD:-}"
  [research]="${RESEARCH_DB_PASSWORD:-}"
  [signal]="${SIGNAL_DB_PASSWORD:-}"
)

for schema in "${!PASSWORDS[@]}"; do
  if [[ -z "${PASSWORDS[$schema]}" ]]; then
    echo "missing password env var for schema '$schema'" >&2
    exit 1
  fi
done

DB="${POSTGRES_DB:-vajra}"

psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-postgres}" -d "$DB" -q \
  -c "REVOKE ALL ON SCHEMA public FROM PUBLIC" \
  -c "REVOKE ALL ON DATABASE \"$DB\" FROM PUBLIC"

for schema in "${!PASSWORDS[@]}"; do
  role="${schema}_svc"
  psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-postgres}" -d "$DB" -q \
    -v role="$role" -v schema="$schema" -v pw="${PASSWORDS[$schema]}" -v db="$DB" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'role')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role') \gexec
ALTER ROLE :"role" WITH LOGIN PASSWORD :'pw';
GRANT CONNECT ON DATABASE :"db" TO :"role";
CREATE SCHEMA IF NOT EXISTS :"schema" AUTHORIZATION :"role";
ALTER ROLE :"role" SET search_path = :"schema";
SQL
done
