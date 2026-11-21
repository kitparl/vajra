---
title: Docker, compose, CI, secrets
topic: devops-basics
level: beginner
---

# Docker, compose, CI, secrets

## 1. Docker
**Problem:** "Mere laptop par chal raha hai, tumhare par nahi."

**Docker:** app ko uski saari cheezon (Java/Python version, libraries) ke saath ek **dabbe (container)** me band kar deta hai. Jahan bhi dabba chale, wahi hi chalta hai.

**Example:** Tiffin box. Ghar ka khana office me bhi wahi taste.

## 2. docker-compose
Ek file jisme likha hota hai "ye saare dabbe saath me chalao".
Hamare project me: PostgreSQL 18, Kafka, Redis, aur services.

```yaml
services:
  postgres:
    image: postgres:18       # exact tag ya digest pin karo
    environment:
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}   # env se, file me nahi
  redis:
    image: redis:7
```
Ek command (`docker compose up`) se poora system local par chalu.

## 3. CI (Continuous Integration)
Har baar code push hone par **machine khud tests chalati hai.** Test fail to code merge nahi hota.

**Example:** Factory me quality inspector. Kharab piece aage nahi jaane deta.

Hamare liye zaroori tests: order state machine, risk checks, idempotency.

## 4. Secrets (gupt cheezein)
Passwords, exchange API keys, tokens.

**Rules:**
- Kabhi git me commit nahi. (Ek baar push hua to history me hamesha rahta hai.)
- Env variable ya secret manager me rakho.
- Testnet aur production ke secrets **alag**.
- Galti se leak hui to turant **rotate** karo (nayi key banao, purani band).

## 5. Testnet / Paper / Live
| Mode | Paisa | Kab |
|---|---|---|
| **Testnet** | Nakli, exchange ka test environment | Default |
| **Paper** | Nakli, hamare simulator par | Strategy test |
| **Live** | **Asli paisa** | Sabse last, chhoti raashi se |

**Rule:** live trading **default me OFF** hai. Jaan-bujhkar chalu karni padti hai.

## 6. Version pinning
Libraries ke **exact version** likh do (lockfile). Warna kal naya version aaya aur app toot gayi.
- Java: Maven/Gradle lock
- Python: `uv.lock` ya hashes ke saath `requirements.txt`
- UI: `package-lock.json`

## 7. Ek-ek service upgrade
Ek baar me ek hi service ka version upgrade karo, uske tests green ho tab agla. Sab saath upgrade karne par bug kahan se aaya pata nahi chalta.

## Yaad rakhne wali baatein
- Docker = tiffin box, compose = poori thali.
- CI = auto tests.
- Secrets git me kabhi nahi.
- Live OFF by default.
