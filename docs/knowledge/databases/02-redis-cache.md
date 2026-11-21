---
title: Redis aur cache
topic: databases
level: beginner
---

# Redis aur cache

## 1. Redis kya hai?
Bahut tez data store jo data **memory (RAM)** me rakhta hai.

**Example:** PostgreSQL = godown (sab saman safe, par nikalne me waqt). Redis = dukaan ka counter (jo cheezein baar-baar chahiye wo haath ke paas).

## 2. Cache
Jo data baar-baar maanga jaata hai use pehli baar laakar **counter par rakh lo**, taaki baar-baar godown na jaana pade.

**Example:** Latest BTC price ek second me 100 baar maangi jaati hai. Har baar database na jaake Redis se de do.

## 3. Hamare project me kahan?
- Latest price/ticker ka chhota snapshot.
- Session aur short-lived tokens.
- Rate limit counters (exchange ki limit ka dhyaan).
- Temporary locks (jaise scheduler ek hi baar chale).

## 4. Kab use NAHI karna
- **Orders, fills, positions** ka asli record Redis me nahi. Redis restart hone par data ud sakta hai. Asli record PostgreSQL me, jo safe hai.
- Redis me "sach" nahi rakhte. Redis me **sach ki copy** rakhte hain.

## 5. TTL (expiry)
Redis me data ko expire kar sakte ho.
```
SET price:BTCUSD 50010 EX 5     # 5 second baad apne aap gayab
```
Purana price na dikhe, isliye TTL chhota rakhte hain.

## Yaad rakhne wali baatein
- Redis = tez, memory me, temporary.
- Asli record PostgreSQL me, cache Redis me.
- TTL se purana data khud hat jaata hai.
