---
number: 0005
title: Trade lifecycle flow
type: flow
created: 2026-11-21
status: current
---

# Trade lifecycle flow

```
Exchange -> Connector -> Market data -> Signal service -> core (order intent)
   ^                                                          |
   |                                                    Risk approval (sync)
   |                                                          |
   +------------- Exchange <- Order placement <---------------+
                       |
                 Fills / order updates -> positions -> audit -> learning
```

## Steps
1. **Market data:** the connector receives ticks/candles over WebSocket, normalizes them to the common model, stores them and publishes `md.*` events. On disconnect it reconnects and backfills gaps.
2. **Signal:** `signal-service` runs strategies and promoted model versions on the data and publishes `signal.generated` (instrument, side, intended size, confidence, strategy/model version).
3. **Order intent:** `core-service` converts the signal into an order intent with a unique client order id (idempotency key).
4. **Risk approval (sync):** `risk-service` checks limits and returns approve, reject or resize. No answer or error means reject.
5. **Placement:** the connector sends the order to the exchange. State machine: `NEW -> SENT -> ACKED -> PARTIALLY_FILLED -> FILLED`, or `REJECTED` / `CANCELLED` / `EXPIRED`.
6. **Fills and updates:** exchange updates arrive over WebSocket; core updates order and position state and publishes `order.updated`, `fill.created`, `position.updated`.
7. **Reconciliation:** periodically (and after any reconnect) core compares its orders and positions with the exchange and fixes or alerts on mismatches.
8. **Audit and learning:** every signal, decision, order and fill is stored; the research side uses them for evaluation and retraining.

## Failure handling
- **Duplicate or retried requests:** same client order id never creates two orders.
- **Timeout on placement:** order is `UNKNOWN` until reconciled with the exchange; never blindly re-send.
- **Partial fills and rejections:** handled by the state machine, reported to risk and alerts.
- **Exchange down or rate-limited:** back off, stop new orders for that venue, alert.
- **Kill-switch:** cancel open orders, flatten positions, set trading halted; trading resumes only by an explicit manual action.
- **Risk-service unreachable:** fail closed.
