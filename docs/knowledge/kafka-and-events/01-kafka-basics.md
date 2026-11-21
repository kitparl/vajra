---
title: Kafka basics
topic: kafka-and-events
level: intermediate
---

# Kafka basics

## 1. Event kya hai?
"Kuch ho gaya" ka record. **Example:** "BTC ka naya price aaya", "order fill ho gaya".

## 2. Kafka kya hai?
Events ko **sambhal kar rakhne aur bahut logon tak pahunchane** ka system.

**Example (akhbaar):** Akhbaar ek baar chhapta hai, hazaaron ghar me pahunchta hai. Chhapne wale ko pata nahi kaun padhega. Padhne wale apni marzi se padhte hain.

- **Producer** = chhapne wala (event bhejne wali service)
- **Topic** = akhbaar ka section (jaise `fill.created`)
- **Consumer** = padhne wala (event sunne wali service)

## 3. Kafka kyun, seedha call kyun nahi?
Agar `core-service` har naye price par 5 services ko seedha call kare:
- ek slow ho to core bhi slow
- nayi service judi to core ka code badlo

Kafka me core sirf **ek baar** event daalta hai. Kisi ko sunna ho to wo topic sun le. Core ko pata bhi nahi chalta.

## 4. Hamare events
`md.tick`, `md.candle`, `signal.generated`, `order.requested`, `risk.decision`, `order.updated`, `fill.created`, `position.updated`, `model.promoted`, `alert.raised`, `kill_switch.changed`.

## 5. Ek example flow
1. `core-service` → `md.candle` (naya candle aaya)
2. `signal-service` sunta hai → `signal.generated`
3. `core-service` sunta hai, order banata hai
4. fill aane par `fill.created`
5. `gateway-bff` sunta hai → UI ko WebSocket se dikhata hai
6. `research-service` sunta hai → evaluation ke liye store karta hai

## 6. Kya Kafka har cheez ke liye?
**Nahi.** Order approval (`core` ➜ `risk`) **sync call** hai, Kafka ke raaste nahi, kyunki wahan turant "haan/na" chahiye aur na milne par reject karna hai. Kafka order approval ke hot path me nahi hai.

## 7. Dhyaan rakhne wali baatein
- **Duplicate event aa sakta hai.** Consumer ko aise banao ki same event do baar aaye to bhi galti na ho (idempotent). Example: `fill.created` do baar aaya to position do baar na badhe. Event ki unique id se check karo.
- **Order ki guarantee:** ek hi order ke events ek hi partition me jayein (key = order id), taaki aage-peeche na ho.
- **Schema:** events ka format Protobuf me `contracts/` me hai.

## Yaad rakhne wali baatein
- Producer bhejta hai, consumer sunta hai, topic beech me.
- Kafka = loose connection. Risk approval = sync call.
- Consumer hamesha duplicate ke liye taiyaar rahe.
