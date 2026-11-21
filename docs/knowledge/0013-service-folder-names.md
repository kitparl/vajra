---
number: 0013
title: Service folder names (Sanskrit)
type: decision
created: 2026-11-21
status: current
---

# Service folder names (Sanskrit)

Superseded by: 0025 (for names inside code, config and compose; folder names unchanged)

## Decision
Service folders under `services/` use short Sanskrit names. The role name (`core-service` etc.) is still used in docs, configs and conversation; both refer to the same deployable.

| Folder | Service (role name) | Stack | DB schema | Meaning |
|---|---|---|---|---|
| `services/brahma/` | core-service | Spring Boot | `core` | the creator: orders, positions and fills originate here |
| `services/maryada/` | risk-service | Spring Boot | `risk` | limit / boundary: approves or vetoes every order |
| `services/dvara/` | gateway-bff | Spring Boot | `gateway` | the door: single entry point for the UI |
| `services/manthan/` | research-service | FastAPI | `research` | churning: data is churned into features and models |
| `services/shankha/` | signal-service | FastAPI | `signal` | conch: announces signals |

## Rules
- DB schema names stay functional (`core`, `risk`, ...), not the Sanskrit names, so schema/role ownership stays obvious.
- Each service's `README.md` title shows both names: `# core-service (brahma)`.
- Corrects the folder list in 0012; responsibilities in 0004 are unchanged.
