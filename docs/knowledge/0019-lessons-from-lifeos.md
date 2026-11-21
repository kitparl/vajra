---
number: 0019
title: Lessons from LifeOS (same VPS)
type: learning
created: 2026-11-21
status: current
---

# Lessons from LifeOS (same VPS)

LifeOS (owner's other project) runs on **the same 4 GB VPS**: systemd unit `lifeos` (uvicorn, 2 workers on 127.0.0.1:8000), Caddy for TLS on 80/443, optional Postgres/Redis/judge containers.

## Applied in Phase 1
- **Migrations as a separate step before start.** LifeOS ran schema changes from every worker's startup and hit races (dead `/health`); it now needs a file lock and a pre-deploy schema step. Vajra Python services run `alembic upgrade head` before uvicorn (0016).
- **Fail fast on bad config.** LifeOS refuses to start in production with default secrets. Vajra fails startup on any missing DB variable (0016).
- **No host port clashes.** LifeOS publishes 5432/6379 (dev). Vajra's base compose publishes nothing; dev uses 15432/16379.

## To apply in the deployment phase
- Deploy script with: pre-flight (build + import check) before touching the running version, health check with retries (cold JVM start on a small VPS is slow), automatic rollback to the last good commit, a history log (`timestamp status commit duration`).
- Reuse the existing Caddy as the single TLS entry point; Vajra's UI/gateway become another Caddy site, not a second proxy on 80/443.
- Keep data outside the repo tree (volumes / `/var/lib/...`) so deploys never wipe it; plan `pg_dump` backups.

## Open
- **Memory budget (0014) must be redone with LifeOS's real usage.** Needed from the VPS: `free -h`, `systemctl status lifeos` memory, `docker stats --no-stream`. Until then, 0014 limits stay but are known to be optimistic.
