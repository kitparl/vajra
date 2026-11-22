# Order lifecycle (Order ki poori zindagi)

**Level:** intermediate to advanced

## Files
| File | Topic |
|---|---|
| `01-state-machine-idempotency-reconciliation.md` | Order states, duplicate order se bachna, exchange se hisaab milana |
| `02-paper-order-ka-safar.md` | Paper trading, order NEW se FILLED, idempotency ka chhota example, PnL ka hisaab |
| `03-reconciliation-aur-unknown-order.md` | UNKNOWN order, reconciler ka 60 second ka hisaab, kill ka venue |

## Ek line me saar
Trading me **model ki galti se zyada operational galtiyan** (duplicate order, purana state) paisa dubati hain. Isliye order system sabse zyada test hota hai.
