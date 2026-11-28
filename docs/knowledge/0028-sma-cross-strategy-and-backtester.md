---
number: 0028
title: SMA-cross strategy, shared decision code and the backtester (Phase 4)
type: decision
created: 2026-11-28
status: current
---

# SMA-cross strategy, shared decision code and the backtester

Phase 4 proves the path **strategy → backtest → paper/testnet** with one simple strategy. Making money is not the goal of this phase.

## Decisions
- **One decision function.** `services/strategies-py` (`vajra_strategies`) holds `SmaCross.decide(closed, position)`. It is pure: no I/O, no clock and no logging. signal-service (live) and research-service (backtest) both call it, so they cannot disagree on a decision.
- **Strategy:** SMA crossover on closed 1-minute candles, fast 20 and slow 60 by default. It is long or flat with 1 contract.
  - BUY only when the position is 0. SELL only when the position is > 0. A short position never trades.
  - Only a cross on the **last** candle counts, so an old cross is never acted on.
  - `decide` reads only the last `slow + 1` candles. A test pins this, and it is what makes the backtest O(n).
  - **Gap rule:** if those candles span more than `slow + 5` minutes, there is no decision. This lives inside `decide`, so live and backtest skip the same gaps, for example after an outage.
- **Core data only via REST.** `vajra_common.core_client.CoreClient` reads `/api/v1/candles` (in 1-day windows), `/api/v1/positions` and `/api/v1/instruments`. It raises `CoreUnavailable` for every transport error, every 5xx and any invalid JSON. Neither Python service touches `core.*` tables.
- **90 days of history.** core-service's `CandleSyncService.HISTORY` is 90 days. Existing installs get a one-time **backward fill** per process start: it walks newest window first and stops when Delta returns nothing. A failure only logs a WARN; the forward sync still runs, and the fill retries on the next run. On the laptop, 90 days were covered about 30 s after start.

## Live runner (`SmaCrossRunner`, signal-service)
- Every 60 s it fetches the last `slow + 8` minutes of candles and the position for the `TRADING_MODE` venue.
- **The newest stored candle is never decided on.** core writes the current minute as a snapshot and rewrites it with final values only on the next sync. So the newest row may not be final, while every older row is. Every decision uses `candles[:-1]` (and closed-by-clock).
- If the newest final candle is older than **4 minutes** (`MAX_SIGNAL_AGE`), the tick does nothing (stale data).
- There is at most one signal per candle: `signal_log.candle_time`, plus a partial unique index as a backstop.
- **Outbox expiry:** every publish, whether a republish or a new row, first checks the row's age against a fresh clock. The age is taken from `candle_time`, or from `generated_at` when `candle_time` is not set. A row older than 4 minutes is marked `expired_at` with a WARN and never sent. Heartbeat uses the same routine. See 0029 for why.
- `STRATEGY` (`sma_cross` | `heartbeat` | `none`) is the only switch, and it defaults to `sma_cross`. **The stop switch is now `STRATEGY=none`.** `HEARTBEAT_ENABLED` from 2.4.0 no longer exists.

## Backtester (research-service, laptop only)
- `POST /api/v1/backtests` with `{symbol, start, end, fast, slow, slippage_bps, fee_rate}`. `GET /api/v1/backtests/{id}` returns a stored run.
  - The fields are named `start`/`end`, because `from` is a Python keyword.
  - The fee defaults to the instrument's taker fee (0.0005 on testnet). Slippage defaults to 2 bps.
- **Fill rule:** a decision at candle i fills at candle i+1's **open**. Slippage is applied against us. Fee per fill = `fee_rate × size × contract_value × price`. A position still open at the end is closed at the last close.
- **End of range:** `end` is clamped to the current minute. When the request reaches the live edge, the newest candle is dropped, by the same snapshot rule as live.
- **Validation (422):**
  - `start >= end`
  - a range longer than 180 days
  - naive datetimes
  - bad fast/slow values, or a negative fee or slippage
  - unknown symbol
  - "not enough candles"

  Core errors give 503.
- **Storage:** every run is stored in `research.backtest_run`, and its trades in `research.backtest_trade`. Decimals are stored as strings.

## First result (BTCUSD, Delta testnet, 90 days, defaults, 2026-11-28)
| Metric | Value |
|---|---|
| Candles | 129,595 (4 missing minutes) |
| Trades | 1,526 |
| Gross P&L | −154.96 USD |
| Fees | 117.77 USD |
| Net P&L | −272.73 USD |
| Max drawdown | 276.55 USD |
| Win rate | 15.8 % |
| Run time | 5.1 s (API, including fetching from core) |

As expected, SMA 20/60 on 1-minute candles whipsaws: about 17 trades a day, and fees alone are larger than any edge. The result is not funding-adjusted (`funding_included=false`).

## Live demo (PAPER, BTCUSD, `SMA_FAST=3 SMA_SLOW=8`, 2026-11-28)
| | Decision candle | Live fill | Backtest fill (same window) |
|---|---|---|---|
| BUY | 02:08 | 60169.5 at 02:10:11 | 60182.03 at 02:09 open (+2 bps) |
| SELL | 02:20 | 60130 at 02:22:12 | 60122.47 at 02:21 open (−2 bps) |

- **The decisions are the same:** both paths acted on the same candles.
- **Live fills about 1 candle later than the backtest assumes.** A candle becomes final only after core's next sync, and the runner ticks every 60 s on top of that.
- After the demo, signal-service was set back to the defaults (20/60). Both positions were flat.
- Memory check OK: 1223 MiB in total.

## Follow-ups
- **Backtest fill timing.** Model the live latency by filling at candle i+2's open, or record live-vs-backtest slippage per trade. The current i+1 open is about 1 minute earlier than live.
- **Maximum signal age on the consumer side (top priority).** core and risk place an order for any `signal.generated` message, however old. A signal stuck in Kafka while core is down still becomes an order. Add a deterministic age rule on `generated_at`, with an audit record. This has been open since 0026. Superseded by: 0030
- **Missed candles in live.** Each tick decides only on the newest final candle. When the runner's 60 s loop and core's 60 s sync drift past each other, one candle can be skipped and its cross missed. Fix: decide on every newly final candle since the last one evaluated.
- **Position read before a pending BUY fills.** If a cross down comes on the next candle, it is missed (a long-only contract). Expiring a stale SELL also leaves the position long until the next cross down.
- **Funding** is not modelled. There is no walk-forward and no parameter search (Phase 6).
- **Minor:**
  - The runner's fetch window is about 1 minute short of the gap rule in one narrow edge case (8 → 10 extra minutes).
  - The backtest's live-edge check is off by one for a non-minute `end` just before the current minute.
  - Runner DB calls run on the event loop.
  - The backward fill is unpaced (about 60 Delta calls per symbol at the first start).
  - A gap of 2000 minutes or more in the middle of history stops the backward fill until the next restart.
