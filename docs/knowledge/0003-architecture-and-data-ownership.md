---
number: 0003
title: Architecture and data ownership
type: design
created: 2026-11-21
status: current
---

# Architecture and data ownership

## Shape: modular monolith plus a few separate processes
Not full microservices. Separate processes only where there is a real reason (safety, runtime, resource profile).

| Deployable | Framework | Why separate |
|---|---|---|
| `core-service` | Spring Boot (Java 25) | Modular monolith: connectors, market data, OMS, instruments, positions. |
| `risk-service` | Spring Boot (Java 25) | Must survive core bugs and veto orders; owns kill-switch. |
| `research-service` | FastAPI (Python 3.14) | Heavy compute: features, backtests, training, model registry, AI research layer. |
| `signal-service` | FastAPI (Python 3.14) | Live strategy runner and model inference; emits signals only. |
| `gateway-bff` | Spring Boot (Java 25) | Dashboard API, WebSocket fan-out to the UI, notifier (Telegram/email), scheduler. |
| `ui` | Angular 22 | Trading terminal. |

## Data ownership (one Postgres 18 cluster, one schema per service)
Each service has its own schema, its own DB role with rights only on that schema, and its own migrations.

| Schema | Owner | Holds |
|---|---|---|
| `core` | core-service | exchanges, instruments, accounts, orders, fills, positions, market data (candles/ticks), audit events |
| `risk` | risk-service | limits, risk decisions, breaches, kill-switch state |
| `research` | research-service | feature definitions, datasets, experiments, backtest runs, model registry and metrics, AI research notes |
| `signal` | signal-service | strategy definitions and versions, strategy runs, signals, shadow-mode results |
| `gateway` | gateway-bff | users, UI preferences, watchlists, alert rules, notification log, scheduled jobs |

**Rules:** no cross-schema reads/writes, no cross-schema foreign keys, no shared tables. Other services get data through APIs or events. If a service needs another's data often, it keeps a local read model built from events.

## Communication
- **Sync (gRPC/REST):** `core-service` asks `risk-service` to approve each order before sending. Risk approval is a hard dependency: if risk is unreachable, the order is rejected.
- **Async (Kafka, Protobuf):** market data, signals, order and fill updates, model promotions, alerts. Kafka is not in the order-approval hot path.
- **Contracts:** all message and API schemas live in `contracts/` and are generated for Java and Python (plus a TypeScript client for the Angular UI only).

## Key events (draft)
`md.tick`, `md.candle`, `signal.generated`, `order.requested`, `risk.decision`, `order.updated`, `fill.created`, `position.updated`, `model.promoted`, `alert.raised`, `kill_switch.changed`.

## Security basics
- Exchange API keys: least privilege (read vs trade), IP whitelisted, stored in a secret manager, never in the repo.
- Separate testnet and production credentials and configs.
- Service-to-service auth between deployables.
