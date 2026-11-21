---
number: 0016
title: Database roles, schemas and migrations (Phase 1)
type: design
created: 2026-11-21
status: current
---

# Database roles, schemas and migrations

## Roles and schemas
`infra/postgres/init/01-roles-and-schemas.sh` runs on the first start of an empty Postgres volume (and is safe to re-run). For each service it creates:

| Schema | Login role | Service |
|---|---|---|
| `core` | `core_svc` | brahma |
| `risk` | `risk_svc` | maryada |
| `gateway` | `gateway_svc` | dvara |
| `research` | `research_svc` | manthan |
| `signal` | `signal_svc` | shankha |

- The role **owns** its schema and has `search_path` set to it. No grants on any other schema.
- `REVOKE ALL ON SCHEMA public FROM PUBLIC` and `REVOKE ALL ON DATABASE ... FROM PUBLIC`: nobody can create tables in `public`.
- Passwords come from env (`CORE_DB_PASSWORD`, ...). The script aborts if one is empty.
- The script only creates schemas and roles. **Tables come only from each service's migrations.**

Guard: `infra/postgres/test/role-isolation.sh` (also in CI) proves every role can use its own schema and cannot create or read in any other schema or in `public`. It was checked by injecting a leak (`GRANT ... ON SCHEMA core TO risk_svc`): the test failed as expected.

## Migrations
| Stack | Tool | Where | History table |
|---|---|---|---|
| Spring Boot | Flyway | `src/main/resources/db/migration/V<n>__*.sql` | `<schema>.flyway_schema_history` |
| FastAPI | Alembic | `<service>/alembic/versions/` | `<schema>.alembic_version` |

- **Python services run Alembic as a separate step before the server starts** (container command: `alembic -c alembic.ini upgrade head && exec uvicorn ...`), never inside the FastAPI lifespan. Lesson from LifeOS (0019): several workers running schema changes at startup raced and left `/health` dead. Alembic has no lock of its own.
- Flyway runs on Spring Boot startup; it takes a Postgres lock, so concurrent starts are safe.
- Phase 1 baselines (`V1__baseline.sql`, `0001_baseline.py`) create no tables; they only prove the history table lands in the service's own schema.

## Fail fast on missing config
Every service needs `DB_URL`, `DB_USER`, `DB_PASSWORD`. A missing one stops startup with a message naming it:
- Java: `services/common` (`RequiredEnvironment`). Needed because Spring keeps an unresolved `${DB_PASSWORD}` as literal text and fails later with an unrelated "connection refused".
- Python: `vajra_common.settings.DatabaseSettings` (pydantic-settings, all fields required).

## Health
`/actuator/health` (Java) and `/health` (Python) return 200 `UP` only when the database answers, otherwise 503 `DOWN`. Connection timeout is 3 s so the check never hangs.
