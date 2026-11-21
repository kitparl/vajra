---
number: 0018
title: Phase 1 build decisions, versions and memory measurements
type: decision
created: 2026-11-21
status: current
---

# Phase 1 build decisions, versions and measurements

## Versions in use (verified 2026-11-21)
| Component | Version | Note |
|---|---|---|
| Java | 25.0.5 | laptop: Homebrew `openjdk@25`; images: Temurin 25 |
| Spring Boot | 4.1.3 | 4.2.1 not released yet; rule in 0002 says wait for 4.2's first patch |
| Maven | 3.10.x | |
| protobuf | Java 4.36.2 / Python 7.36.2 | same 36.x line |
| Python | 3.14.10 | |
| FastAPI 0.165.0, SQLAlchemy 2.1.9, psycopg 3.3.9, Alembic 1.20.1, pydantic-settings 2.16.3, uvicorn 0.54.0 | pinned | |
| uv | 0.13.9 | |
| PostgreSQL | 18.7 (alpine) | |
| Kafka | 4.4.0 (`apache/kafka`, KRaft single node) | |
| Redis | 8.8.4 (alpine) | |

Where versions live (one place per ecosystem): Java in `services/pom.xml`; Python in `services/pyproject.toml` + member `pyproject.toml` files, locked in `services/uv.lock`; image tags in `infra/compose/.env.example` (tests read them from there too).

## Build layout
- Maven parent `services/pom.xml`: modules `../contracts/java`, `common`, `brahma`, `maryada`, `dvara`.
- uv workspace `services/pyproject.toml`: members `common-py`, `shankha`, `manthan`; `vajra-contracts` comes in as a path dependency (uv workspace members must live under the workspace root).
- Python service code lives in packages named after the service (`shankha/`, `manthan/`), not `app/`, because both share one workspace venv.
- **Shared startup helpers** (corrects "no shared code yet" in 0012): `services/common` (Java, `RequiredEnvironment`) and `services/common-py` (`vajra_common`: settings, engine, health, Alembic helper). Only startup plumbing that every service needs; no business logic may go there.
- One Dockerfile per language: `services/java.Dockerfile` and `services/python.Dockerfile`, with `ARG SERVICE`.
- Compose: `infra/compose/compose.yaml` (base, VPS-ready, no published ports) + `compose.dev.yaml` (laptop ports). Wrapper: `scripts/compose.sh`.
- Dev ports: dvara 8080, brahma 8081, maryada 8082, shankha 8091, manthan 8092, Postgres **15432**, Redis **16379** (5432/6379 are used by LifeOS dev on the same laptop). Dev ports bind to `127.0.0.1` only, never the LAN.

## Memory at idle (laptop, arm64, `scripts/check-memory.sh`)
| Container | Used | Limit |
|---|---|---|
| postgres | 53 MiB | 512 MiB |
| kafka | 329 MiB | 512 MiB |
| redis | 12 MiB | 64 MiB |
| brahma | 140 MiB | 384 MiB |
| maryada | 130 MiB | 384 MiB |
| dvara | 134 MiB | 384 MiB |
| shankha | 66 MiB | 256 MiB |
| **Total** | **864 MiB** | 2,496 MiB |

JVM flags (`java.Dockerfile`): `MaxRAMPercentage=50` (heap 192 MB of 384), `MaxDirectMemorySize=32m`, `MaxMetaspaceSize=96m`, `ReservedCodeCacheSize=48m`, `Xss512k`, SerialGC, C1 only (revisit C2 for Phase 2 hot paths). Heap + metaspace + code cache + direct memory + stacks stay below the limit, so a heap OOM is a Java error instead of a kernel kill (exit 137). Idle is not load: re-measure on the VPS (amd64) with real traffic before going live. LifeOS shares the VPS (0019), so the real free RAM is less than 0014 assumed.

## Laptop setup
`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home` (keg-only formula; the Temurin cask needs sudo).
