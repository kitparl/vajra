---
number: 0006
title: Self-learning loop
type: design
created: 2026-11-21
status: current
---

# Self-learning loop

**Principle:** learning happens offline in `research-service`. Live trading uses only frozen, versioned models. A model never edits itself in production.

## Pipeline
1. **Feature store:** point-in-time correct features (no future data), built from market data, funding rates, order book stats, news tags.
2. **Training:** walk-forward training and validation, scheduled (daily/weekly) or on drift triggers.
3. **Model registry:** every model has an id, version, training data range, parameters, metrics, code hash.
4. **Evaluation:** out-of-sample metrics with realistic costs (fees, slippage, funding): Sharpe, max drawdown, hit rate, turnover.
5. **Shadow mode:** the candidate emits signals but sends no orders.
6. **Champion vs challenger:** challenger runs on paper alongside the current live model.
7. **Promotion gate:** promote only if pre-defined thresholds pass; first live exposure is small capital.
8. **Drift monitoring:** if live performance or data distribution degrades, the model is automatically demoted back to shadow or retired.

## What learns
- Signal models (start with gradient boosting, move to sequence models only if justified).
- Regime detection (trending, ranging, high/low volatility) that switches or scales strategies.
- Capital allocator across strategies (bandit-style), within risk caps.
- Execution model (slippage, fill probability).
- AI research layer (LLM APIs): news/filing tagging, post-trade analysis, strategy-idea drafts. Drafts must pass backtest and the gate like anything else.

## What never learns
The risk engine and kill-switch. Limits are config, changed only by a human.

## Guardrails
- Simple models first, strict validation, small capital.
- Keep a record of every experiment (also the failed ones) to control data-snooping.
- LLM-derived features need forward testing (paper) because of lookahead leakage in historical backtests (see 0009).
