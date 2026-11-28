---
title: Purana signal mat bhejo (outbox me signal ki umar)
topic: kafka-and-events
level: intermediate
---

# Purana signal mat bhejo

## Chhota example
Subah 9 baje dost ne message likha: "abhi market jao, sabzi sasti hai". Phone ka network band tha, to message shaam 6 baje gaya. Ab wo message **nuksaan** karega, kyunki sabzi kab ki khatam ho chuki hai.

Trading signal bhi aisa hi hai: **der se pahuncha signal, na pahunche signal se bura hai.**

## Hamare saath kya hua (28 Nov, sirf PAPER)
1. Kafka ka data `/tmp` me ja raha tha, volume me nahi, kyunki compose me `KAFKA_LOG_DIRS` set nahi tha. Container dobara bana to saare topics mit gaye.
2. Heartbeat signal banata raha, par Kafka me bhej nahi paya, isliye signal outbox (`signal_log`) me ruke rahe.
3. Topics wapas aaye to 56 purane signal ek saath chale gaye, kuch to 6 din purane the. Isse 9 paper orders fill hue. Risk ki rate limit ne baaki rok liye.

## Fix
- Compose me `KAFKA_LOG_DIRS: /var/lib/kafka/data` set kiya, taaki data volume me rahe. Kafka container dobara banakar check kiya: topics bache rahe.
- **Har bhejne se pehle umar check hoti hai.** Signal 4 minute se purana ho to wo bheja nahi jata, sirf `expired_at` mark hota hai aur WARN log hota hai. Ye niyam heartbeat aur sma_cross dono par lagta hai.

## Seekh
- Outbox me rakhe message ki bhi **expiry date** honi chahiye.
- Sirf volume mount karna kaafi nahi hai. Ek baar container dobara banao aur dekho ki data bacha ya nahi.
- Aage: core-service bhi purana signal reject kare (dohri suraksha). Ye 0028 ka follow-up hai.

Formal record: `../0029-kafka-storage-and-stale-outbox-incident.md`.
