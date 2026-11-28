#!/usr/bin/env bash
# Fails unless the base compose stack publishes exactly one host port: ui on 127.0.0.1:8088 -> 8080.
set -euo pipefail
cd "$(dirname "$0")/.."

docker compose -f infra/compose/compose.yaml --env-file infra/compose/.env.example config --format json |
python3 -c '
import json, sys

services = json.load(sys.stdin).get("services", {})
published = sorted(
    (name, p.get("host_ip", ""), str(p.get("published", "")), p.get("target"))
    for name, svc in services.items()
    for p in svc.get("ports", [])
)
expected = [("ui", "127.0.0.1", "8088", 8080)]
if published != expected:
    print(f"FAIL: published ports {published}, expected {expected}")
    sys.exit(1)
print("OK: only ui is published, on 127.0.0.1:8088 -> 8080")
'
