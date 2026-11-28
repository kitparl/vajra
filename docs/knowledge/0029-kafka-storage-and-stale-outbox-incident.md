---
number: 0029
title: "Incident: Kafka lost its topics and old heartbeat signals became paper orders"
type: learning
created: 2026-11-28
status: current
---

# Incident: Kafka lost its topics and old heartbeat signals became paper orders

## What happened (2026-11-28, laptop, PAPER only)
1. During the Phase 4 demo, core logged `UNKNOWN_TOPIC_OR_PARTITION`, and Kafka had only `__consumer_offsets` left.
2. After `kafka-init` recreated the topics, signal-service's republish step sent **56 old heartbeat outbox rows**, the oldest from 2026-11-22.
3. That burst became 58 PAPER orders: 9 filled (5 SELL, 4 BUY) and the rest were stopped by risk's `ORDER_RATE_LIMIT`. The PAPER BTCUSD position went from −1 to −2.
4. DELTA_TESTNET was not touched. Its position stayed 0.

## Root causes
1. **Kafka never used its volume.** Compose mounted `kafkadata` at `/var/lib/kafka/data` but never set `KAFKA_LOG_DIRS`. The `apache/kafka` image writes to `/tmp/kraft-*-logs` by default, so every container **recreate** wiped all topics. The 2.5.0 `mem_limit` change recreated Kafka and caused this. The 2.5.0 release note's "data stays in the kafkadata volume" was wrong.
2. **Outbox rows had no maximum age for heartbeat.** While the topics were missing, every publish failed, so the rows stayed in the outbox. The expiry check only covered rows with a `candle_time` (sma_cross). Heartbeat rows (`candle_time` null) were exempt, so days-old rows were sent the moment Kafka worked again.

## Fixes
- Compose now sets `KAFKA_LOG_DIRS: /var/lib/kafka/data`. The image user (uid 1000 `appuser`) owns that path. Verified: after `--force-recreate` of Kafka, all topics were still there.
- One shared outbox routine (`signal_service/outbox.py`) is used by Heartbeat and SmaCrossRunner. Before **every** send, it compares the row's age with a fresh clock reading. The age comes from `candle_time` if set, else from `generated_at`. A row older than 4 minutes gets `expired_at` with a WARN and is not sent. The producer `request_timeout_ms` is 10 s, so one stuck send cannot use up the age budget.

## Lessons
- **An outbox without a maximum age is a time machine.** For trading signals, a late signal is worse than a lost one: always expire, never replay.
- **Defence in depth:** the consumer (core or risk) should also reject old signals by `generated_at`. That fix is the top follow-up in 0028.
- **Check that a volume is really used.** A mount alone proves nothing. Recreate the container once and check that the data survived.
- Risk's order rate limit stopped most of the burst. It is a real safety net, not just a formality.
