---
title: Risk gate aur kill-switch kaise chalta hai
topic: risk-management
level: beginner
---

# Risk gate aur kill-switch kaise chalta hai

## 1. Risk gate = bank ka transaction limit
**Example:** Aapke bank app me "ek transaction me max Rs 1 lakh" ka limit hota hai. Aap 5 lakh bhejoge to bank mana kar dega, chahe aapko kitna bhi bharosa ho apne aap pe. Bank ka rule seedha hai, usme "mood" nahi hota.

Hamare project me `maryada` (risk-service) wahi bank hai. `brahma` har order bhejne se pehle usse puchta hai. Rules is order me check hote hain:
1. Request sahi hai? (size 1 se kam ya ajeeb number to `INVALID_REQUEST`)
2. Venue allowed hai? (`PAPER`, `DELTA_TESTNET`)
3. Kill-switch dabaa hai? to `HALTED` (sirf flatten orders ko chhoot)
4. Order size 1 se bada? `ORDER_SIZE_LIMIT`
5. Position 5 contracts se upar jayegi? `POSITION_LIMIT` (position kam karne wale order hamesha allowed)
6. 60 second me 10 se zyada orders? `ORDER_RATE_LIMIT`

**Fail-closed:** agar maryada jawab hi na de (band hai, 500 ms me late), to order **reject** (`RISK_CHECK_FAILED`). Jaise bank ka server down ho to wo "chalo paisa bhej do" nahi bolta.

## 2. Kill-switch = ghar ka main switch
**Example:** Ghar me aag lagne ka dar ho to aap main switch off karte ho, phir baad me aaram se dekhte ho. Ek baar switch dabaa diya, to koi bhi (chahe kisi ne dobara on karne ki koshish ki ho) use bypass nahi kar sakta jab tak aap khud resume na karo.

Kill dabane par:
- `halted = true` turant save hota hai (kill hamesha jeetta hai).
- `brahma` khule orders cancel karta hai aur positions **flatten** karta hai.
- Naye orders `HALTED` se reject.

**Flatten ka matlab:** saari open positions ko ulta order daal ke zero kar dena. Buy 1 khula hai to Sell 1 (reduce-only). Ye orders limits se exempt hain, kyunki ye sirf risk kam karte hain; agar limit hi inhe rok de to kill-switch bekaar ho jayega.

**Resume** (`/kill-switch/resume`): trading wapas shuru. Agar resume ke beech koi dobara kill daba de, to **409** aata hai aur system halted hi rehta hai (compare-and-set).

**Core unreachable:** agar `brahma` nahi mila, maryada halted rehta hai (safe side). Bas kill dobara dabao.

## 3. Project me kahan hai
- Rules, limits, kill/resume: `services/maryada/` (schema `risk`, tables `risk_limit`, `risk_decision`, `kill_switch_event`).
- Gate client aur local halt: `services/brahma/.../risk/MaryadaRiskGate`, `core.trading_state`.
- Tokens: `RISK_API_TOKEN`, `CORE_INTERNAL_TOKEN` (env me, kabhi commit nahi). Sirf `/actuator/health` bina token ke khula hai.
- Poori detail: `docs/knowledge/0024-risk-gate-and-kill-switch.md`.
