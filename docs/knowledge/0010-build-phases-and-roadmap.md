---
number: 0010
title: Build phases and roadmap
type: design
created: 2026-11-21
status: current
---

# Build phases and roadmap

Each phase ends with something demoable. Do not start a phase before the previous one is stable on testnet.

1. **Foundation:** monorepo, `contracts/`, docker-compose (Postgres 18, Kafka, Redis), CI, per-service schemas and migrations, CLAUDE.md, docs structure.
2. **Core on Delta testnet:** Delta connector, market data ingestion, instrument master, OMS state machine with idempotency and reconciliation, simulated exchange and paper mode.
3. **Risk:** `risk-service` with limits and kill-switch, sync approval in the order path, tests for fail-closed behavior.
4. **Signals and backtesting:** `signal-service` with a simple strategy, `research-service` backtester with realistic costs, same strategy code in backtest and live.
5. **Terminal UI + gateway:** `gateway-bff`, Angular app with watchlist, chart, order ticket, positions, risk and kill-switch screens, alerts.
6. **Learning pipeline:** feature store, training, model registry, shadow mode, champion vs challenger, promotion gates, drift monitoring.
7. **Second exchange:** proves the connector abstraction.
8. **AI research layer:** news/filing tagging, post-trade analyst, strategy-idea drafts (all behind backtest and gates), capital allocator.
9. **Small live capital** on Delta India after weeks of paper results matching backtests.
10. **Indian stocks:** broker connector under SEBI's algo framework; re-check rules first.

## Definition of done for any phase
Tests pass, testnet/paper verified, audit trail complete, knowledge doc added, release note added (after the user confirms).
