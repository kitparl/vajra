---
number: 0026
title: Heartbeat strategy and the Kafka signal path (Phase 2 slice 4)
type: decision
created: 2026-11-22
status: current
---

# Heartbeat strategy and the Kafka signal path

Slice 4 proves the whole signal-to-fill chain on the paper venue with a deliberately dumb strategy, so Phase 4 strategies can reuse the same path unchanged.

## Flow
1. `signal-service` (`services/shankha`) runs the **heartbeat** strategy. One interval after startup, then every `HEARTBEAT_INTERVAL_SECONDS` (default 900, minimum 10), it alternates BUY and SELL of `HEARTBEAT_SIZE` (default 1) on `HEARTBEAT_SYMBOL` (default BTCUSD). The alternation survives a restart (it continues from the last row in `signal.signal_log`).
2. It writes the signal to `signal.signal_log` and publishes `vajra.events.v1.SignalGenerated` to topic `signal.generated`, then sets `published_at`. The Kafka producer connects lazily, so the service starts even when Kafka is down; unpublished rows are retried on a later tick.
3. `core-service` consumes `signal.generated` (group `core-service`, manual ack) and builds an `OrderIntent` with source `SIGNAL`, MARKET/IOC and idempotency key `signal-<signal_id>`. It goes through the normal order path: risk-service approval, paper venue, fill, position.
4. Every state change writes a `core.audit_event` row in the same DB transaction as the change (transactional outbox). The outbox publisher (every 1 s) sends rows with `published_at IS NULL` to the topic named by `event_type` (`order.updated`, `fill.created`, `position.updated`), keyed by symbol, then marks them published.

## Why outbox and manual commit
- Outbox: the DB write and the "event to send" commit together, so a Kafka outage cannot lose an event or send one for a rolled-back change. Events are at-least-once; consumers de-duplicate.
- Manual ack: the consumer acks only after the order was handled (placed, or deliberately skipped). A crash before the order row exists re-delivers the message and the retry places it normally; the idempotency key stops a second order. A unique index on `orders.signal_id` is the second guard. Limit: once the NEW row is committed, a replay only finds the stored order and does not re-run it (see Poison-message policy).

## Idempotency key `signal-<id>`
The same signal can arrive twice (redelivery, producer retry). Same key means the existing order is returned and no second order is created. Result in the demo: exactly one order per signal id.

## Poison-message policy
A message that cannot be parsed or is invalid is logged and skipped (acked), so one bad record never blocks the partition. A transient failure is not acked and is retried (FixedBackOff 2 s x 5), then skipped. This retry helps only before the order row exists. If the failure happens after NEW is committed (for example a DB blip while recording the risk decision), the retry or redelivery finds the existing order, does not re-process it, logs `signal <id>: order <id> stuck in <state>, not re-processed` at ERROR and acks. The order stays NEW/APPROVED/SENT (no venue call was made, so it is safe) and needs manual action or slice 5 reconciliation.

## Topics
`kafka-init` (one-shot compose service, `infra/kafka/create-topics.sh`) creates `signal.generated`, `order.updated`, `fill.created`, `position.updated`: 1 partition each, 7-day retention. One partition keeps ordering simple for a single-instance system; revisit before scaling out.

## Config
signal-service env: `KAFKA_BOOTSTRAP` (required), `TRADING_MODE` (PAPER or DELTA_TESTNET, else startup fails), `HEARTBEAT_*`. core-service: `KAFKA_BOOTSTRAP`, `SIGNALS_ENABLED` (default true). The demo interval is set only in the shell (`HEARTBEAT_INTERVAL_SECONDS=60 scripts/compose.sh up -d`), never in `.env`.

## Demo result (interval 60 s, paper)
- Heartbeat orders alternated BUY, SELL, all `FILLED`, `source=SIGNAL`, with `risk_decision_id`; the position returned to 0 after each pair; `signal_log` ids matched order `signal_id`s.
- signal-service restart: the next side continued the alternation.
- Kafka stopped for about 60 s: core-service stayed healthy, a manual REST order filled, the heartbeat row generated during the outage stayed unpublished, and after Kafka came back it was published and became exactly one order. The outbox drained to 0 unpublished rows.

## Measured memory (idle, `scripts/check-memory.sh`, OK)
| Service | Used MiB | Limit MiB |
|---|---|---|
| postgres | 68 | 512 |
| kafka | 382 | 512 |
| redis | 12 | 64 |
| core-service | 192 | 384 |
| risk-service | 153 | 384 |
| gateway-bff | 131 | 384 |
| signal-service | 70 | 256 |
| **Total** | **1008** | |

Kafka grew from 329 MiB (0018) to 382 MiB (74% of its limit) now that topics are in use. Watch it; do not raise the limit without a measured reason (VPS is 4 GB, shared).

## Follow-ups
- `order.updated`, `fill.created`, `position.updated` have no consumer yet (gateway-bff will use them for the UI).
- Real strategy signals go through this same path in Phase 4 (the heartbeat is only a pipeline test).
- The consumer retry path (no ack, FixedBackOff 2 s x 5, then skip) has only a unit test, no integration test.
- The outbox publisher is single-instance only (no row locking for multiple publishers).
- During a partial outage, signals can be sent out of order; they are de-duplicated by `signal_id`, but order of arrival is not guaranteed.
- Stuck orders (see Poison-message policy) are only logged at ERROR; slice 5 reconciliation should pick them up. Superseded by: 0027
- Stale signals: there is no maximum signal age, so after a long outage queued signals become MARKET orders at the current price (risk's 10/min cap limits the burst). Add a max age before Phase 4. Superseded by: 0029 (producer-side expiry done; consumer-side max age still open, see 0028)
- The heartbeat alternates on the last generated signal, not the last filled one, so an odd number of rejected signals shifts the paper position (risk's 5-contract cap bounds it).
- The outbox stops at a row it can never send (topic deleted, record too large) and only logs WARN every second. Escalate after N failures.
- compose passes `TRADING_MODE` to signal-service but not to core-service. Wire it in slice 5. Superseded by: 0027
