# common-py (vajra_common, Python)

Startup plumbing shared by shankha and manthan. **No business logic here.**

- `settings.DatabaseSettings` — `DB_URL`, `DB_USER`, `DB_PASSWORD` from env, all required.
- `db.create_engine_from` — pool of 5, 3 s connect timeout, pre-ping.
- `health.health_router` — `GET /health`: 200 UP / 503 DOWN.
- `migrations.run_online` / `run_migrations` — Alembic in the service's own schema.

Why it exists: `docs/knowledge/0016-database-roles-and-migrations.md`, `docs/knowledge/0018-phase1-build-decisions-and-measurements.md`.
