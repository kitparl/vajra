---
number: 0004
title: Service responsibilities
type: service
created: 2026-11-21
status: current
---

# Service responsibilities

Folder under `services/` is given in each heading (naming: 0013).

## core-service — `services/brahma/` (Spring Boot)
**Does:** exchange connectors (Delta first), market data ingestion and storage, instrument master, order management (state machine, idempotent placement, reconciliation with the exchange), positions, paper-trading simulated broker, audit event log.
**Owns:** schema `core`.
**Does not:** decide trades, change risk limits, run ML.

## risk-service — `services/maryada/` (Spring Boot)
**Does:** approve or reject every order: position size, exposure, leverage, daily loss, order rate, per-strategy and per-exchange caps. Owns the kill-switch (cancel all open orders, flatten positions, halt trading).
**Owns:** schema `risk`.
**Rules:** deterministic, no ML/LLM in the decision path, fail-closed (no answer means reject).

## research-service — `services/manthan/` (FastAPI)
**Does:** feature store (point-in-time correct), backtesting engine, model training (walk-forward), model registry, evaluation and promotion-gate checks, drift reports, AI/LLM research layer (news and filing tagging, post-trade analysis, strategy-idea drafts).
**Owns:** schema `research`.
**Does not:** talk to exchanges or place orders.

## signal-service — `services/shankha/` (FastAPI)
**Does:** run strategies and load promoted model versions, produce signals (including shadow-mode signals), report strategy health.
**Owns:** schema `signal`.
**Does not:** place orders; it only publishes `signal.generated`.

## gateway-bff — `services/dvara/` (Spring Boot)
**Does:** dashboard REST API and WebSocket gateway for the UI, user and preference management, watchlists, alert rules, Telegram/email notifier, scheduler (market sessions, token refresh, end-of-day jobs).
**Owns:** schema `gateway`.
**Does not:** contain order, fee or position logic. It reads and displays; actions go to core/risk through their APIs.

## ui — `ui/` (Angular)
Trading terminal: watchlist, charts, order ticket, positions, orders, strategies and models, backtests, risk and kill-switch, exchange health, alerts, audit viewer.

## Cross-cutting rules
- A service that needs another service's data uses its API or an event-built read model.
- Anything that moves money or changes an order lives in Spring Boot.
- Every action that matters writes an audit event.
