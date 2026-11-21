---
title: PostgreSQL, schema aur migrations
topic: databases
level: beginner
---

# PostgreSQL, schema aur migrations

## 1. Database kya hai?
Data ko **table** me rakhne ki jagah. **Example:** Excel jaisa, jisme har sheet ek table hai, har row ek record.

Table `orders`:
| id | symbol | side | qty | status |
|---|---|---|---|---|
| o1 | BTCUSD | BUY | 0.5 | FILLED |
| o2 | ETHUSD | SELL | 2 | NEW |

## 2. PostgreSQL
Ek bharosemand, free database. Hum version **18** use karte hain. Isme ACID milta hai, yaani transaction ya to **poora hota hai ya bilkul nahi**.

**Example (bank transfer):** A ke ₹100 kate aur B me nahi aaye, ye kabhi nahi hona chahiye. Transaction dono kaam saath-saath karta hai ya dono rok deta hai.

## 3. Schema
Ek database ke andar **alag-alag almariyan.** Hamare paas ek cluster me:
`core`, `risk`, `research`, `signal`, `gateway` schemas. Har service ka apna schema.

```sql
-- core service ka table
CREATE TABLE core.orders (
  id            text PRIMARY KEY,
  client_order_id text UNIQUE NOT NULL,   -- idempotency ke liye
  symbol        text NOT NULL,
  qty           numeric(28,10) NOT NULL,
  status        text NOT NULL
);
```
Dhyaan: **paise aur quantity ke liye `numeric`**, `float` nahi.

## 4. DB role (taala-chaabi)
Har service ka **apna DB user** hota hai jise sirf **apne schema** par haq hai. `signal-service` ke user ko `core` schema padhne ka bhi haq nahi. Isse galti se bhi doosre ka data nahi chhua ja sakta.

## 5. Migration (database ki "git history")
**Problem:** Kal aapne table me nayi column daali. Teammate ke laptop par ye nahi hai. Production par bhi nahi. Sab gadbad.

**Solution:** Database ke har badlav ko **numbered file** me likho, aur tool use apne aap chalaye.
- Java services: **Flyway**
  ```
  V1__create_orders.sql
  V2__add_fee_column.sql
  ```
- Python services: **Alembic**

**Rules:**
- Purani migration file kabhi edit nahi karte. Naya badlav = nayi file.
- Ek service ka ek hi migration tool.
- Ek service doosri service ke schema ki migration nahi chalati.

## 6. Index (tez khojna)
Index = kitab ke peeche "shabdkosh". Bina index ke 1 crore rows me dhundhna dheema hota hai.
```sql
CREATE INDEX idx_orders_symbol ON core.orders(symbol);
```

## 7. Time series data (candles/ticks)
Bahut zyada data aata hai. Pehle PostgreSQL ki native **partitioning** (data ko mahine-wise tukdon me baatna) se kaam chalate hain. TimescaleDB tabhi, jab PG 18 ke saath support confirm ho.

## Yaad rakhne wali baatein
- Ek cluster, 5 schemas, 5 DB roles.
- Paise ke liye `numeric`.
- Badlav hamesha migration file se, purani file edit nahi.
