---
title: Docker images, compose aur memory limit
topic: devops-basics
level: beginner
---

# Docker images, compose aur memory limit

## Chhota example
Ek chhota fridge (VPS ki RAM) hai aur 7 log usme khana rakhte hain. Agar sabke liye **shelf tay** na ho, to ek aadmi poora fridge bhar dega aur baaki ka khana bahar.
Docker ki `mem_limit` wahi shelf hai.

## Hamare naap (laptop par, idle)
| Container | Use | Shelf (limit) |
|---|---|---|
| postgres | 53 MiB | 512 MiB |
| kafka | 329 MiB (slice 5: ~430 MiB) | 768 MiB (pehle 512, 2.5.0 me badhaya) |
| redis | 12 MiB | 64 MiB |
| core-service / risk-service / gateway-bff | ~135 MiB each | 384 MiB each |
| signal-service | 66 MiB | 256 MiB |
| **Kul** | **864 MiB** | |

`scripts/check-memory.sh` ye table banata hai aur fail ho jaata hai agar koi container apni shelf ka 90% cross kare.
Dhyan do: ye **khaali baithe** ka naap hai. Asli trading ke time zyada lagega, aur LifeOS bhi isi VPS par chalta hai.

## Commands
```
cp infra/compose/.env.example infra/compose/.env   # passwords badlo
scripts/compose.sh up -d --build                   # sab chalu
scripts/check-memory.sh                            # memory check
scripts/compose.sh --profile research up -d research-service  # research sirf laptop par
scripts/compose.sh down                            # band
```

Compose me services ke naam role wale hain (`core-service`, `risk-service`, …), folder ke Sanskrit naam nahi (0025).

## Do compose files kyun?
- `compose.yaml`: base, jo baad me VPS par chalega. Database ke ports bahar nahi khulte (suraksha).
- `compose.dev.yaml`: sirf laptop ke liye ports kholta hai. Postgres `15432` aur Redis `16379` par, taaki LifeOS ke `5432`/`6379` se na takraaye.

Formal record: `../0018-phase1-build-decisions-and-measurements.md`.
