---
number: 0023
title: Order management and paper trading in brahma (Phase 2 slice 2)
type: decision
created: 2026-11-21
status: current
---

# Order management and paper trading in brahma

## Decision
`brahma` (core-service) owns orders, fills and positions (schema `core`, migration `V4__oms.sql`). Orders go through a strict state machine and are filled by a **paper venue** that uses live Delta testnet quotes. No real exchange order is placed in this slice. REST API: `POST /api/v1/orders`, `POST /api/v1/orders/{id}/cancel`, `GET /api/v1/orders[?state=]`, `GET /api/v1/orders/{id}`, `GET /api/v1/positions`, `GET /api/v1/pnl`.

## State machine
`NEW -> APPROVED -> SENT -> ACKED -> PARTIALLY_FILLED -> FILLED`, plus `RISK_REJECTED` (risk gate said no, from `NEW`), `REJECTED`, `UNKNOWN` (send outcome unclear, from `SENT`), `CANCEL_REQUESTED`, `CANCELLED`, `EXPIRED`. Three transitions were added because the original diagram implies them:
- `ACKED -> FILLED` (full fill without a partial fill first)
- `PARTIALLY_FILLED -> PARTIALLY_FILLED` (further partial fills)
- `CANCEL_REQUESTED -> FILLED` (a fill races the cancel and wins)

Partial fills are covered by state machine tests only; the paper venue always fills in full.

## Idempotency
Header `Idempotency-Key` is required: 1-64 chars of `[A-Za-z0-9_-]`. `client_order_id = "v" + first 31 hex chars of SHA-256(key)` (32 chars). Same key and same body returns the existing order (HTTP 200, `201` for the first call). Same key with a different body is `409`. Validation errors are `400 {"error": ...}`, never 500. A MARKET order with explicit `GTC` is a 400 by design (market orders are always IOC).

## Paper fill rules
- Market order: taker fill at the touch (BUY at best ask, SELL at best bid), taker fee.
- Limit order: if it crosses the book it fills as taker at the touch; otherwise it rests as ACKED (GTC) and the scheduled matcher fills it as maker at its **limit price** when the market reaches it. IOC limits that do not cross expire.
- Quotes older than 10 s, or with null / zero / negative bid or ask, give `STALE_PRICE` for a new order (rejected); a resting order simply waits for a fresh quote.
- Fill step makes up to 3 attempts (2 retries) on optimistic-lock conflicts (order and position carry a `version`).

## PnL rules
Position size is signed (long positive). Average entry is volume weighted when adding. Closing realizes `closed * contractValue * (price - avg) * sign(oldSize)`. A flip closes the old side first, then opens the remainder at the fill price.
Example: long 2 @ 50000, sell 5 @ 51000 -> realized 2 (with contractValue 0.001: 2 * 0.001 * 1000), new position short 3 @ 51000.
Unrealized = `size * contractValue * (mark - avg)` with the 10 s quote mark; null when no fresh quote. `GET /pnl`: `netPnl = realized - fees + unrealized`; `unrealizedPnl` and `netPnl` are null if any non-flat position has no fresh mark.
Scale note: BigDecimal values leave the API as plain strings with DB scale (e.g. fees `0.0500100`), so clients compare numerically.

## Audit
Every order state change, fill and position change writes an `audit_event` row **in the same transaction** as the change itself (CLAUDE.md rule 6). Publishing these rows to Kafka is deferred to slice 4 (first consumer).

## Placeholder risk gate
Until the risk service exists (slice 3), `PaperOnlyRiskGate` approves PAPER orders and rejects everything else with `NO_RISK_SERVICE`. `TRADING_MODE` defaults to `PAPER`; slice 2 has no trading venue for `DELTA_TESTNET`. Live trading stays off.

## Known follow-ups (slices 3 and 5)
- Re-check reduce-only at fill time (a position can change between place and fill).
- Sweeper for orders stuck in non-terminal intermediate states.
- `CANCEL_REQUESTED` has no exit on a venue error yet.
- Partial-fill sizing in the paper venue.
- Order view returns the internal order record (includes `idempotencyKey`, `version`); trim when the gateway/UI consumes it.

## Measured
`brahma` container after rebuild and demo: about 168 MiB of 384 MiB (43%) via `scripts/check-memory.sh`.
