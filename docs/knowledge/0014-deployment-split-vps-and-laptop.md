---
number: 0014
title: Deployment split - live path on VPS, research on laptop
type: decision
created: 2026-11-21
status: current
---

Correction: LifeOS also runs on this VPS, so the budget below is optimistic. To be redone with measured LifeOS usage, see 0019 and 0018.

# Deployment split: live path on VPS, research on laptop

## Decision
The production VPS (4 GB RAM, 2 vCPU, see `.secret_notes`) runs **only the live trading path**.
`research-service` (feature building, backtests, training) runs on the developer laptop and only ships **versioned, frozen model artifacts** to the VPS through the model registry gate (CLAUDE.md rule 5).

## Runs where
| Component | VPS | Laptop |
|---|---|---|
| PostgreSQL 18 | yes | yes (dev) |
| Kafka (single node, KRaft) | yes | yes (dev) |
| Redis | yes | yes (dev) |
| core (brahma), risk (maryada), gateway (dvara) | yes | yes (dev) |
| signal-service / shankha (inference only) | yes | yes (dev) |
| ui (static files via Nginx) | yes | yes (dev) |
| research-service / manthan (backtest, training) | **no** | yes |

## Memory budget on the VPS (starting point, tune with real numbers)
| Component | Limit |
|---|---|
| PostgreSQL 18 | 512 MB |
| Kafka (small heap) | 512 MB |
| Redis | 64 MB |
| core-service, risk-service, gateway-bff | ~384 MB each (~1.15 GB) |
| signal-service | 256 MB |
| ui (Nginx) | 32 MB |
| OS + headroom | ~1.5 GB |

Every container gets an explicit memory limit. JVMs get explicit heap flags.

## Why
- Training and backtests use lots of RAM and CPU in bursts. On a 4 GB box this can push the OS to kill a process, and the killed process could be `risk-service` or `core-service` while orders are open. That is not acceptable for a system that handles money.
- Training is offline by design (0006): live uses frozen model versions only, so research does not need to be on the live box.

## Consequences
- Research needs read access to market data. It gets it by exporting/syncing data from `core` through an API or event stream into its own `research` schema on the laptop. It never reads the `core` schema directly (0003).
- A model reaches the VPS only as a registry version that passes the gate (shadow, paper, small live).
- If the VPS is upgraded later, research can move to it; this decision should then be revisited and superseded, not edited.
