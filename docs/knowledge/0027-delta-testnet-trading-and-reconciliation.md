---
number: 0027
title: Delta testnet trading and reconciliation (Phase 2 slice 5)
type: decision
created: 2026-11-22
status: current
---

# Delta testnet trading and reconciliation

Slice 5 lets core-service trade on Delta Exchange India **testnet** (`https://cdn-ind.testnet.deltaex.org`, demo funds) and keeps our records in line with the venue. Default stays `TRADING_MODE=PAPER`: `.env.example` ships `TRADING_MODE=PAPER`, and a value exported in the shell wins over `.env`. Set `TRADING_MODE=DELTA_TESTNET` in the shell (never in `.env`) together with `DELTA_TESTNET_API_KEY` / `DELTA_TESTNET_API_SECRET` (in the git-ignored `.env`).

## Polling instead of WebSocket
Order state comes from REST polling: a market order polls up to 3 s after placing, resting orders are checked every 2 s (`OpenOrderChecker`), and the reconciler runs every 60 s. WebSocket would give faster updates but needs reconnect, sequence and replay handling. At 1-contract, a few orders per hour, polling is simpler and enough (same reasoning as 0022). The reconciler is the safety net either way.

## Reconciler rules (`Reconciler`, every 60 s, first run 10 s after start)
1. Orders stuck in NEW/APPROVED for 60 s are closed as not sent. Orders stuck in SENT for 30 s go to UNKNOWN (`STUCK_SENT`).
2. UNKNOWN orders on Delta are looked up by `client_order_id`. Found: moved to the real state (reason `RECONCILED`) and the fill is recorded. Not found for 60 s: REJECTED `NOT_FOUND_AT_VENUE`. Place is never re-sent.
3. Delta fills we have no row for are recorded only for an order we know, through the same path as the checker, so a fill is recorded exactly once.
4. Positions are compared. A difference is an ERROR line with both values. Our records are never overwritten.
Each run writes one INFO line `Reconcile done: checked=.. resolved=.. mismatches=..`. Mismatch ERROR lines are the alerts until the Phase 5 notifier.

## Kill-switch changes
Flatten targets `position.venue()` (not the trading mode), skips `tradingEnabled`, cancels at the venue, and reports `flattened=false` if a flatten is rejected (for example `RATE_LIMITED`) or an order is still in flight.
CANCEL_REQUESTED orders are re-cancelled: if a venue cancel fails (5xx, timeout) the order stays CANCEL_REQUESTED, and then the open-order checker re-sends the venue cancel on its next pass while Delta still shows the order open (at most every 2 s per order), and a second kill re-sends it too. Paper behaviour is unchanged.

## Operational rule: switching back to PAPER
Switch back to PAPER only when Delta is flat and has no open orders. Otherwise the reconciler logs an ERROR every 60 s for the unmatched Delta position or order, and kill reports `flattened=false`.

## Delta facts observed on testnet (2026-11-22)
- Duplicate `client_order_id` while the first order is open: HTTP 400, `{"error":{"code":"duplicate_client_order_id","context":{"client_order_id":...}},"success":false}`. The first order is untouched.
- Re-using a `client_order_id` after the first order was **cancelled** is accepted and creates a new order. So a client id is unique only among live orders, and our own idempotency key remains the real duplicate guard.
- `GET /v2/orders/client_order_id/{id}` finds open, cancelled (`cancelled`, reason `cancelled_by_user`) and filled (`closed`) orders. This is what makes UNKNOWN resolution work.
- BTCUSD: product id 84, `contract_value` 0.001 BTC, `tick_size` 0.1, `position_size_limit` 5000. ETHUSD: product id 1699, `contract_value` 0.01 ETH, `tick_size` 0.05, `position_size_limit` 4000.
- `/v2/products` has no minimum order size field. Size is an integer number of contracts, so the minimum is 1.
- A market order is returned as `market_order`, `ioc`, with a protective `limit_price` far from the market (about 121677 for a 61k BTCUSD buy). Fills are TAKER with a fee of about 0.0359 on a 1 contract BTCUSD market order.

## Demo result (testnet, 1 contract each)
- Manual MARKET BUY then SELL: both FILLED on `DELTA_TESTNET`, our fill price and fee equal the Delta values, positions matched (reconciler `mismatches=0`), flat at the end.
- Heartbeat at 60 s: BUY and SELL heartbeat orders FILLED on `DELTA_TESTNET`.
- Connection cut mid-order: order stayed SENT (the DB write was cut too), after 30 s STUCK_SENT to UNKNOWN, then RECONCILED to ACKED, then FILLED with exactly 1 fill and matching position, about 75 s after reconnect.
- Kill-switch with a position of 1: `halted=true`, `flattened=true`, Delta position 0, resume OK.

## Measured memory (dev stack, after restart in PAPER mode)
core-service 179 MiB of 384 (46%), risk-service 163, gateway-bff 136, signal-service 71. Total 1083 MiB, `check-memory.sh` OK. No core-service limit raised.

**Kafka limit raised 512 → 768 MiB.** After the slice 4 + 5 load, the final check showed Kafka at 480 of 512 MiB (93%, `check-memory.sh` FAIL). The cgroup showed 426 MiB anon (real JVM memory: 320 MiB fixed heap plus non-heap), not page cache, and the container had hit its limit 51 times (no OOM kill yet). After the raise: 367 of 768 MiB (47%), stack total 1018 MiB, check OK. That is well inside the 4 GB VPS shared with LifeOS. If Kafka keeps growing, check the non-heap usage before raising again.

## Follow-ups
- `comparePositions` only checks trading-enabled instruments, so a position on a disabled instrument is not compared.
- The startup check is warn-only for 401/403 codes other than `SignatureExpired` and `invalid_api_key` (for example IP not whitelisted). This is fail-safe, because orders then get rejected.
- No WebSocket: order and position updates are polled.
- Partial fills are not supported because the maximum order size is 1. Per-fill rows (one row per Delta fill, with a running filled size) are needed before raising the size limit.
- Alerts are ERROR log lines until the Phase 5 notifier.
- Halt TOCTOU: an order can pass the halt check, then kill lands before the venue call. It is detected through `flattened=false` on the kill result, and the next kill or the reconciler fixes it.
- Stale-signal maximum age from 0026 is still open.
- When a place call returns but the first status poll fails (`DeltaApiException`), the order is left ACKED and the checker settles it within about 1 s. The exception message is not logged; consider logging the Delta error code at WARN.
- The first reconcile run after an outage can print transient mismatch ERRORs (position and fill for an order still SENT) before the stuck-order step resolves it, one cycle later.
- The kill-switch flattens every venue with an open position, so a leftover PAPER position is also closed when testing on testnet.
- Reconnecting a container with `docker network connect` needs `--alias <service>`, otherwise other services cannot resolve its compose name (risk-service reported "core unreachable").
