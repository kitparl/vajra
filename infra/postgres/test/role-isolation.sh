#!/usr/bin/env bash
# Verifies CLAUDE.md rule 1: each service role can use only its own schema.
# Usage: run from repo root with the compose postgres running.
set -uo pipefail

COMPOSE=("$(dirname "$0")/../../../scripts/compose.sh")
SCHEMAS=(core risk gateway research signal)
failures=0

psql_as() { # role, sql -> exit code of psql
  "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -q -U "$1" -d vajra -c "$2" >/dev/null 2>&1
}
expect() { # expected(ok|fail), description, role, sql
  if psql_as "$3" "$4"; then got=ok; else got=fail; fi
  if [[ "$got" != "$1" ]]; then echo "FAIL: $2 (expected $1, got $got)"; failures=$((failures + 1)); fi
}

# Probe tables owned by each service, created through its own role.
for s in "${SCHEMAS[@]}"; do
  expect ok "${s}_svc creates probe in own schema" "${s}_svc" "CREATE TABLE IF NOT EXISTS ${s}.probe(x int)"
done

for s in "${SCHEMAS[@]}"; do
  role="${s}_svc"
  expect ok   "$role uses own schema"            "$role" "CREATE TABLE ${s}.t(x int); DROP TABLE ${s}.t"
  expect fail "$role creates table in public"    "$role" "CREATE TABLE public.t(x int)"
  for o in "${SCHEMAS[@]}"; do
    [[ "$o" == "$s" ]] && continue
    expect fail "$role creates table in $o"      "$role" "CREATE TABLE ${o}.t(x int)"
    expect fail "$role reads $o.probe"           "$role" "SELECT * FROM ${o}.probe"
  done
done

# Init script must be idempotent (safe on an existing volume).
if ! "${COMPOSE[@]}" exec -T postgres bash /docker-entrypoint-initdb.d/01-roles-and-schemas.sh >/dev/null 2>&1; then
  echo "FAIL: init script is not idempotent"; failures=$((failures + 1))
fi

for s in "${SCHEMAS[@]}"; do psql_as "${s}_svc" "DROP TABLE IF EXISTS ${s}.probe"; done

if (( failures > 0 )); then echo "role isolation: $failures failure(s)"; exit 1; fi
echo "role isolation: OK"
