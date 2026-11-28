#!/usr/bin/env bash
# Phase 1 memory check (0014): every VPS-set container must be healthy and idle below 90% of its limit.
# Usage: scripts/check-memory.sh   (after scripts/compose.sh up -d --build)
set -euo pipefail
cd "$(dirname "$0")/.."

EXPECTED=(postgres kafka redis core-service risk-service gateway-bff signal-service ui)
THRESHOLD_PERCENT=90
WAIT_SECONDS=180

container_id() { scripts/compose.sh ps -q "$1" 2>/dev/null || true; }
to_mib() { # "123.4MiB" | "1.2GiB" | "512KiB" -> integer MiB
  awk -v v="$1" 'BEGIN { n = v + 0; if (v ~ /GiB$/) n *= 1024; else if (v ~ /KiB$/) n /= 1024; else if (v ~ /B$/ && v !~ /iB$/) n /= 1048576; printf "%d", n }'
}

deadline=$((SECONDS + WAIT_SECONDS))
for svc in "${EXPECTED[@]}"; do
  id="$(container_id "$svc")"
  if [[ -z "$id" ]]; then echo "FAIL: $svc is not running"; exit 1; fi
  until [[ "$(docker inspect -f '{{.State.Health.Status}}' "$id")" == "healthy" ]]; do
    if (( SECONDS > deadline )); then echo "FAIL: $svc not healthy after ${WAIT_SECONDS}s"; exit 1; fi
    sleep 3
  done
done

failures=0
total_mib=0
printf '%-15s %10s %10s %6s\n' SERVICE USED_MIB LIMIT_MIB PCT
for svc in "${EXPECTED[@]}"; do
  id="$(container_id "$svc")"
  used="$(docker stats --no-stream --format '{{.MemUsage}}' "$id" | awk '{print $1}')"   # e.g. 123.4MiB
  limit_bytes="$(docker inspect -f '{{.HostConfig.Memory}}' "$id")"
  used_mib="$(to_mib "$used")"
  limit_mib=$((limit_bytes / 1048576))
  pct=$((used_mib * 100 / limit_mib))
  total_mib=$((total_mib + used_mib))
  printf '%-15s %10s %10s %5s%%\n' "$svc" "$used_mib" "$limit_mib" "$pct"
  if (( pct >= THRESHOLD_PERCENT )); then echo "FAIL: $svc uses ${pct}% of its limit"; failures=$((failures + 1)); fi
done
echo "TOTAL used: ${total_mib} MiB"

if (( failures > 0 )); then exit 1; fi
echo "memory check: OK"
