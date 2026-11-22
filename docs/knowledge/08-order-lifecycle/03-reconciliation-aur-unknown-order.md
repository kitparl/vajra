---
title: Reconciliation aur UNKNOWN order
topic: order-lifecycle
level: advanced
---

# Reconciliation aur UNKNOWN order

## Chhota example
**Example:** Aapne dukaan par UPI payment kiya, net toot gaya. Aapko pata nahi paisa kata ya nahi. Aap dobara pay nahi karte, pehle bank statement dekhte ho. Statement me dikha to paisa kata, nahi dikha to nahi.

Order me bhi yahi hai. Exchange ko order bheja, jawab nahi aaya (timeout, net band). Order ko hum **UNKNOWN** kehte hain. Hum **dobara order nahi bhejte**, kyunki ho sakta hai pehla fill ho chuka ho aur double position ban jaye.

## Reconciler kya karta hai
Har 60 second me ek job (Reconciler) exchange se hisaab milati hai:
1. Atke hue orders (NEW/APPROVED 60 s, SENT 30 s) ko saaf karti hai. SENT atka to UNKNOWN banta hai.
2. UNKNOWN order ko `client_order_id` se exchange par dhundti hai. Mila to asli state (FILLED, CANCELLED...) laga deti hai aur fill ek hi baar likhti hai. 60 second tak nahi mila to REJECTED (`NOT_FOUND_AT_VENUE`).
3. Exchange ke wo fills jo hamare paas nahi hain, sirf **hamare jaane hue order** ke liye likhti hai.
4. Hamari position aur exchange ki position match karti hai. Alag mili to **ERROR log** (dono value ke saath). Hamara record kabhi chupke se nahi badalti.

## Testnet par kya dekha
Order bhejte hi connection kaat diya. Order SENT me atka, 30 s baad UNKNOWN, fir reconciler ne exchange se poocha: "fill ho chuka hai". Order FILLED hua, fill ek hi baar, position match. Lagbhag 75 second me.

## Kill-switch aur venue
Kill dabane par position jis venue par hai (PAPER ya DELTA_TESTNET) usi par band hoti hai. Agar flatten reject ho (jaise rate limit), to kill `flattened=false` batata hai, jhooth me "true" nahi.

## Vajra me kahan
`Reconciler`, `OpenOrderChecker`, `DeltaTradingVenue` in `services/brahma`. Detail aur Delta ke facts: `docs/knowledge/0027-delta-testnet-trading-and-reconciliation.md`.
