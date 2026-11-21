---
number: 0009
title: Backtesting and learning pitfalls
type: learning
created: 2026-11-21
status: current
---

# Backtesting and learning pitfalls

Starter list of things that make trading systems look good on paper and fail live. Add to this (as new numbered files) when we learn more.

- **Lookahead bias:** using information that was not available at decision time (future candles, revised data, labels computed with future prices). Build features point-in-time.
- **LLM lookahead leakage:** an LLM trained on later data "knows" how old news played out, so historical sentiment backtests look unrealistically good. Validate such features by forward testing on paper.
- **Overfitting and data snooping:** trying many strategies or parameters until one works. Keep a log of every experiment, hold out data, prefer simple models, use walk-forward validation.
- **Unrealistic costs:** include fees, slippage, spread, and funding for perpetuals; model partial fills and latency.
- **Liquidation and margin effects:** with leverage, a drawdown can liquidate before it recovers. Backtest margin, not only P&L.
- **Non-stationarity (regime change):** markets change; a model good last quarter can fail. Monitor drift and demote automatically.
- **Survivorship bias:** only testing instruments that still exist or are liquid today.
- **Sample size and noise:** few trades make metrics meaningless; report confidence ranges, not only point estimates.
- **Backtest vs live mismatch:** use the same strategy code path, compare backtest, paper and live fills regularly.
- **Feedback loops:** a self-learning system trained on its own trades can reinforce its own bias; keep exploration limited and capital capped.
- **Operational risk beats model risk:** missed reconnects, duplicate orders and stale state cause more losses than most bad signals. Reconciliation and idempotency are first-class features.
