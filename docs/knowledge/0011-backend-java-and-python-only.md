---
number: 0011
title: Backend uses only Java and Python (no NestJS)
type: decision
created: 2026-11-21
status: current
---

# Backend uses only Java and Python (no NestJS)

## Decision
Every backend service is written in **Java (Spring Boot)** or **Python (FastAPI)**. NestJS, Node.js and TypeScript are **not** used for any backend service.

## What changed
| Service | Before | Now |
|---|---|---|
| `gateway-bff` | NestJS (Node LTS, TypeScript) | **Spring Boot (Java 25 LTS)** |
| Migrations for gateway | TypeORM or Prisma | **Flyway** (same as core and risk) |

All other services are unchanged: `core-service`, `risk-service` (Spring Boot), `research-service`, `signal-service` (FastAPI).

## Why Spring Boot for `gateway-bff` (and not FastAPI)
- It already needs WebSocket fan-out, scheduled jobs, auth and notifications. Spring has mature support for all of these (WebSocket/STOMP or SSE, `@Scheduled` or Quartz, Spring Security).
- Two Java services already exist, so tooling (Flyway, build, CI, logging, metrics) is shared and the team learns one stack less.
- FastAPI stays for compute and ML work, where Python libraries matter. A gateway has no ML.
- If this ever feels wrong, switching `gateway-bff` to FastAPI is a small change because it owns no order logic and talks only through APIs and events.

## What stays the same
- `ui` is still Angular (a frontend, not a backend). Node.js and TypeScript remain only as **UI build tooling**.
- Data ownership rules (0003) are unchanged: `gateway-bff` owns schema `gateway`.
- `gateway-bff` still contains no order, fee or position logic. It forwards user actions to `core-service` and `risk-service`.
- Protobuf contracts are generated for Java and Python. A TypeScript client is generated only for the UI.

## Files updated for this decision
`CLAUDE.md`, `0002`, `0003`, `0004`.
