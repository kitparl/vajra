---
number: 0024
title: Risk gate and kill-switch (maryada + brahma, Phase 2 slice 3)
type: decision
created: 2026-11-21
status: current
---

# Risk gate and kill-switch

Every order from `brahma` (core-service) is approved by `maryada` (risk-service) before it can reach a venue. `maryada` is deterministic (no ML, no randomness): same request plus same stored state gives the same decision. It owns the kill-switch. Live trading stays OFF; this slice runs on the paper venue.

## Approval API
`POST /api/v1/approvals` on maryada (port 8082). `brahma` calls it from `MaryadaRiskGate` with a **500 ms** connect and read timeout. Every request and answer is stored in `risk.risk_decision` (audit, rule 6).

## Rules and their order (first failing rule rejects)
1. `INVALID_REQUEST`: missing or unknown fields, `size < 1`, or position arithmetic overflow (`Math.*Exact`; an overflow once approved `Long.MAX_VALUE + 1`, now rejected).
2. `VENUE_NOT_ALLOWED`: venue not in the allow-list (`PAPER`, `DELTA_TESTNET`).
3. Flatten exemption: `source = KILL_SWITCH` and `reduceOnly = true` is approved here, even while halted.
4. `HALTED`: kill-switch pressed, everything else is rejected.
5. `ORDER_SIZE_LIMIT`: size above 1 contract.
6. `POSITION_LIMIT`: absolute resulting position above 5 contracts per instrument. Reductions (moving toward zero) are always allowed, even from an over-limit position.
7. `ORDER_RATE_LIMIT`: more than 10 approved orders in a rolling 60 s (all instruments).

Any unexpected exception inside the rules is stored and answered as REJECTED with reason `INTERNAL_ERROR` (fail closed).

Limits live in `risk.risk_limit`, seeded by migration: max order size 1, max position 5, 10 orders per 60 s, venues PAPER and DELTA_TESTNET. Learned models can never change them (rule 3).

## Flatten exemption and why
The kill-switch flatten orders (`source = KILL_SWITCH`, `reduceOnly = true`) skip the size, position and rate checks and are approved while halted. They only reduce risk, and if the safety net could be blocked by its own limits (flattening a 5-lot with a 1-lot size limit, or the rate limit hit by a burst) a kill would not flatten.

## Serialisation
Approvals run under `pg_advisory_xact_lock(4711)`, so the rate window and position view cannot be raced by concurrent approvals. Cost: Hikari pool is 5 and approvals are serial, so heavy load makes callers time out, which is fail-closed (reject). A late APPROVED row (answer arrives after brahma's 500 ms timeout) still consumes the rate window.

## Fail-closed paths
- maryada unreachable, timeout (500 ms), non-200, malformed answer, REJECTED with no reason: the order becomes `RISK_REJECTED` with `RISK_CHECK_FAILED`.
- Local halt: brahma keeps `core.trading_state` and rejects locally with `HALTED` even if maryada is down. maryada still enforces too.
- DB down in maryada: approvals fail (reject) and a kill cannot start (kill never reaches core). Consistent with fail-closed.
- Both services refuse to start without `RISK_API_TOKEN` / `CORE_INTERNAL_TOKEN`.

## Auth
Bearer tokens from env: `RISK_API_TOKEN` (callers to maryada, brahma to maryada) and `CORE_INTERNAL_TOKEN` (maryada to brahma `/internal/v1/kill|resume`). Compared with `MessageDigest.isEqual`. Wrong or missing token: 401 `{"error":"unauthorized"}`. Only `/actuator/health` is open.

**Bypass found and fixed:** the first filter checked `getRequestURI().startsWith("/api/")`, so `/api;x=y/...` and `/%61pi/...` skipped it. Fix: **deny-by-default on `servletPath`** (only `/actuator/health` is open), Bearer scheme case-insensitive. The same rule is used for brahma's `/internal/**` filter, with tests for `/internal;x=y/v1/kill` and `/%69nternal/v1/kill`. Lesson: never authorise by raw URI prefix.

## Kill / resume sequence
`POST /api/v1/kill-switch` {"reason"}:
1. Commit `halted = true`, a KILL event and a new `updated_at` **before** taking the lock ("kill always wins": a press is never lost or blocked by an in-flight resume).
2. Take one JVM `ReentrantLock` (maryada is single-instance), re-read the state. If a later resume has already cleared halted (superseded kill), skip core and return the stored status (last action wins, both services consistent).
3. Call brahma `/internal/v1/kill`: brahma halts locally, cancels open orders, and flattens positions with reduce-only orders. Flatten client keys are `kill-` + UUID, created under the lock, so a repeated kill press does not collide or double-submit. Result `{cancelledOrders, flattenOrders, flattened}` is stored as a FLATTEN event.
4. If core is unreachable: maryada stays halted, no flatten recorded, answer is an error; press kill again.

`POST /api/v1/kill-switch/resume`: under the lock, read `updated_at`, call core resume, then clear halted only `where updated_at = :epoch` (compare-and-set). If a kill was pressed meanwhile: **409** "kill pressed during resume; still halted", no RESUME event. Core unreachable: 503, stays halted. `GET /api/v1/kill-switch` shows state and recent events.

## Reduce-only re-check at fill
A reduce-only order is re-checked when it fills: if it would no longer reduce the position (position already flat or flipped), it is cancelled (ACKED to CANCEL_REQUESTED to CANCELLED) instead of opening risk.

## Measured memory (dev stack, idle after demo)
brahma 159 MiB of 384 (41%), maryada 149 MiB of 384 (38%).

## Known follow-ups
**Before Delta live / slice 5**
- Kill cannot flatten a position on a disabled instrument (symbol removed from config) or a venue other than `TRADING_MODE`; flatten should skip `tradingEnabled` for KILL_SWITCH reduce-only and use the position's venue.
- Kill's final "no open order" check misses CANCEL_REQUESTED / UNKNOWN / in-flight orders; reduce-only cancel is local only (fine for paper, not for a real venue).
- An in-flight MANUAL order that passed the risk check before the halt can fill after kill's last re-read; re-check halt in the SENT transition.
- Client timeout (500 ms) vs server work: late APPROVED rows burn the rate window.

**Hardening**
- `RISK_API_TOKEN` is both brahma's credential for approvals and the only credential for kill/resume, so anyone with brahma's env can resume trading. Add a separate operator token for `/api/v1/kill-switch/**` when dvara is wired in.
- An in-flight order approved just before the halt can fill after kill's final check, and kill then reports `flattened=true` while a position is open (paper: press kill again). `everythingFlat` should also count NEW/APPROVED/SENT/UNKNOWN orders. Must fix before slice 5.
- Null request/limits NPE paths rely on the service mapping any exception to reject; flatten exemption also skips the position check by design.
- Kill/resume DB write failing after core resume leaves core resumed and maryada halted with no audit (fails safe). `supersededByResume` reads halted and updated_at in two queries (stale response only).
- JDK read timeout may not bound a stalled body after headers (unverified, low risk).
- REJECTED with null reason is stored with null reason; local halt read sits outside the transition tx; halt/resume not in brahma `audit_event` (maryada `kill_switch_event` is the record).

**Docs / polish**
- Test gaps: events in kill-again, no RESUME event on 503, 401 on GET/resume, blank `CORE_INTERNAL_TOKEN` startup test, invalid body shape assert.
- Redundant 200 check in `MaryadaRiskGate.decide`; equals chain vs Set for venue/source; a few long lines.
