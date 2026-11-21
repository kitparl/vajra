---
number: 0002
title: Tech stack and versions
type: decision
created: 2026-11-21
status: current
---

# Tech stack and versions

> Updated: NestJS removed from the stack. Backend is Java + Python only. See `0011`.

Verified on **2026-11-21** from public release pages. Spring Boot, Python and FastAPI do not have an LTS programme, so for them "stable and recent" is used. Re-verify quarterly and before starting each service.

| Component | Choice | Notes |
|---|---|---|
| Java | **25 (LTS)** | Released 2025-09-16; current LTS, succeeds 21. |
| Spring Boot | **4.2.x** (fallback 4.1.x) | 4.1 has been stable since 2026-06-10. 4.2.0 was reported released 2026-11-19, so wait for the first patch if you want maximum stability. Supports Java 17 to 26. |
| Node.js | **26 (LTS)** (fallback 24 LTS), **UI build tooling only** | Not used for any backend service (see 0011). 26 enters Active LTS in Oct 2026 (support to Apr 2029). |
| TypeScript | **6**, **UI only** | Needed by Angular 22. No TypeScript backend. |
| Angular | **22** | Released 2026-06-03; needs TypeScript 6, drops Node 20; OnPush default; Signal Forms and Resource API stable. |
| Python | **3.14** | Supported to Oct 2030. 3.15 shipped 2026-10-09 but ML library wheels usually lag a new Python, so start on 3.14 and move later. |
| FastAPI | latest 0.x, **pinned** | Needs Python 3.10+ and Pydantic v2. 0.x means minor versions can break; pin exactly. |
| PostgreSQL | **18** | GA 2025-09-25. PostgreSQL 19 was at RC1 in Oct 2026; do not adopt until GA and extension support is confirmed. |
| Kafka, Redis | latest stable at setup | Pin images by digest or exact tag. |
| TimescaleDB (optional) | verify it supports PG 18 before use | If not, use native partitioning first. |
| Protobuf / gRPC | latest stable at setup | Contracts in `contracts/`, generated for Java and Python (and TypeScript for the UI client only). |
| Migrations | Flyway (Spring Boot), Alembic (FastAPI) | One tool per service. |

## Rules
- Pin exact versions in lockfiles (`pom.xml`/Gradle lock, `uv.lock`/`requirements.txt` with hashes, `package-lock.json`).
- Java and Node (UI tooling) use LTS lines only. Do not adopt non-LTS Java or odd Node lines.
- Backend languages: **Java and Python only** (0011).
- Upgrade one service at a time, with its tests green, and log it in `docs/releases`.

## Sources checked
Java 25 LTS (Azul, SD Times), Spring Boot version status pages (HeroDevs, Spring blog, endoflife.ai), Node.js release schedule and release blog, Angular 22 coverage (angular.love, angulararchitects), Python devguide and docs, PostgreSQL 18 release news, FastAPI docs.
