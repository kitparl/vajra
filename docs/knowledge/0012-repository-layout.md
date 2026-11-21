---
number: 0012
title: Repository layout (monorepo)
type: decision
created: 2026-11-21
status: current
---

# Repository layout (monorepo)

Folder names under `services/` corrected per 0013 (Sanskrit folder names).

## Decision
One monorepo. Each deployable lives in its own folder and owns its own build files, migrations and tests.

```
contracts/proto/{events,api}/   Protobuf: Kafka events and service APIs
services/<name>/                brahma (core), maryada (risk), dvara (gateway-bff)  - Spring Boot
                                manthan (research), shankha (signal)                - FastAPI
ui/                             Angular terminal
infra/{compose,postgres}/       docker-compose; Postgres bootstrap (one schema + role per service)
scripts/                        dev helpers
.github/workflows/              CI
docs/{knowledge,releases}/      design records, release notes
```

## Why
- Services grouped under `services/` keeps the root readable and makes the "one folder = one deployable = one schema" rule visible.
- `contracts/` stays at the root because every service and the UI generate code from it; nothing else may define shared shapes.
- `infra/postgres/` only creates schemas and roles. Tables are created by each service's own migrations (Flyway / Alembic), so ownership is never blurred.

## Not decided yet (decide when the first service is scaffolded)
- Java build tool (Maven vs Gradle) and whether the three Spring Boot services share a parent build.
- Python tooling (`uv` assumed from 0002) and whether a shared Python package is needed.
- No `libs/` / `common/` folder yet: add one only when the same code is really needed in two services.
