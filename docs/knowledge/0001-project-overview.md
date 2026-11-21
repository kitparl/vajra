---
number: 0001
title: Project overview
type: design
created: 2026-11-21
status: current
---

# Project overview

## What we are building
A **self-learning, multi-exchange autonomous trading platform**. It ingests market data, produces signals from strategies and learned models, checks every order against an independent risk engine, executes through exchange connectors, and keeps learning from its own results.

- **Phase focus:** crypto derivatives/spot, starting with **Delta Exchange (India)**.
- **Later:** more crypto exchanges, then Indian stocks (via SEBI-compliant broker APIs), other asset classes.
- **AI usage:** mostly through external AI/LLM APIs (news tagging, post-trade analysis, research drafts) plus classic ML models trained in-house.

## Goals
1. Trade safely and autonomously, always inside hard risk limits.
2. Learn continuously, but only through a governed pipeline (train offline, validate, shadow, paper, small live).
3. Add a new exchange or asset class by writing a connector, without touching the core.
4. Be observable: any trade can be replayed and explained from the audit log.
5. Look and feel like a modern trading terminal (see 0008).

## Non-goals (for now)
- High-frequency / co-located latency trading.
- Managing other people's money or offering the platform to third parties.
- Fully LLM-driven order decisions.

## Design principles
- **Safety before cleverness:** the risk engine and kill-switch never learn and cannot be overridden by models.
- **Learning is offline, execution is frozen:** live trading uses versioned, frozen models only.
- **Same code in backtest and live:** removes a whole class of "worked in backtest" bugs.
- **Services own their data:** no shared tables (see 0003).
- **Testnet and paper first:** live trading is off by default.

## What "most powerful" means here
Not novelty. Similar open-source systems already exist (Freqtrade, Hummingbot, Jesse, QuantConnect Lean). Our edge is measured by: out-of-sample risk-adjusted returns, robustness to regime change, safe self-improvement, and engineering quality (reconciliation, idempotency, auditability).

## Main risks
- Overfitting and lookahead leaks in self-learning (see 0009).
- Leverage: a bad model or bug can cause liquidation. Hard limits are mandatory.
- Exchange outages, API changes, rate limits.
- Regulation differs by market (India crypto vs. SEBI algo rules for stocks). Re-check before each new market.

## Disclaimer
This is an engineering project, not financial advice. Use only money that can be lost.
