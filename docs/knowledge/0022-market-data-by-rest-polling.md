---
number: 0022
title: Market data by REST polling from Delta testnet (Phase 2 slice 1)
type: decision
created: 2026-11-21
status: current
---

# Market data by REST polling from Delta testnet

## Decision
`brahma` gets instruments, quotes and candles from the **Delta India testnet public REST API** by polling, not WebSocket:

| Data | Endpoint | Interval | Weight / 5 min |
|---|---|---|---|
| Products (instrument master) | `GET /v2/products?contract_types=perpetual_futures&states=live` | startup + every 6 h | ~0 |
| Quotes (best bid/ask, mark) | `GET /v2/tickers?contract_types=perpetual_futures` | every 2 s | 450 |
| 1-minute candles | `GET /v2/history/candles?resolution=1m` | every 60 s per enabled symbol | 30 |

Delta's budget is 20,000 weight per 5 minutes, so this uses about 2.4%. On HTTP 429 every Delta call pauses for `X-RATE-LIMIT-RESET` ms (60 s if the header is missing).

## Why
- The WebSocket part of Delta's docs could not be read (the docs page is too large to fetch whole), so its URLs, channels and heartbeat are unverified. REST is verified against real responses.
- Polling has no reconnect logic. A missed poll is harmless: candle sync always continues from the last stored candle, so any gap (downtime, Delta outage) is backfilled on the next run.
- The private WebSocket (orders, fills) is still planned for Phase 2 slice 5.

## Testnet only
The base URL `https://cdn-ind.testnet.deltaex.org` is a code constant. Phase 2 has no production URL anywhere, also for market data. Paper fills (slice 2) therefore use testnet prices.

## What the real responses look like (checked 2026-11-21)
- Products: decimals are JSON **strings** (`"contract_value": "0.001"`). No min-size field; Delta sizes are whole contracts, so `min_size = 1`.
- Tickers: `quotes.best_bid/best_ask` and `mark_price` are strings, `close` is a number, `timestamp` is **microseconds**.
- Candles: numbers (floats), `time` in **seconds**, newest first (we sort ascending). Max 2000 per call. A minute with no trade has no candle (ETHUSD on testnet had 3 such minutes in 7 days).
- Testnet BTCUSD: product id 84, contract value 0.001 BTC, tick 0.1. ETHUSD: id 1699, contract value 0.01 ETH, tick 0.05. Fees 0.02% maker / 0.05% taker. 17 live perpetuals in total. Values are read at runtime, never hard-coded.

## Storage
- `core.instrument`: all products, `trading_enabled` only for `vajra.marketdata.symbols` (default BTCUSD, ETHUSD).
- `core.candle_1m`: primary key `(instrument_id, open_time)`, upsert so the open candle is overwritten until it closes. First run backfills 7 days (about 10k rows per symbol, took ~25 s on the laptop).
- Quotes: in memory only (`QuoteCache`); a quote older than 10 s counts as stale.

## Behaviour when Delta is down
Startup and `/actuator/health` do not depend on Delta. Failed polls are logged and retried; the last quote turns stale on its own. The instrument refresh runs every minute until it has succeeded once, then every 6 h. A single malformed ticker is skipped, not fatal for the whole poll.

## Scheduler: one thread per job (lesson)
Spring's default scheduler runs all fixed-delay jobs on **one** thread, also with virtual threads enabled. The 7-day candle backfill (or a slow Delta) then blocked the 2 s quote poller, so quotes went stale. `SchedulingConfig` now uses a `ThreadPoolTaskScheduler` with 3 threads (`vajra-job-*`), one per job. Guarded by `SchedulerConcurrencyIT`.

## Memory
`brahma` with polling running: 147 MiB of 384 MiB (Phase 1 idle: 140 MiB). Laptop, arm64; re-measure on the VPS.

## API
`GET /api/v1/instruments`, `GET /api/v1/candles?symbol=&from=&to=` (ISO-8601, `[from, to)`, max 7 days, 400/404 with `{"error": ...}`). Decimals are returned as plain strings.

## Tests
`vajra.scheduling.enabled=false` turns the polling jobs off in integration tests, so CI never calls Delta. Delta responses are tested with recorded fixtures in `services/brahma/src/test/resources/delta/`.
