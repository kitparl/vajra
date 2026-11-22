---
title: Heartbeat signal ka safar
topic: kafka-and-events
level: intermediate
---

# Heartbeat signal ka safar

## Chhota example
Socho tum roz subah dost ko "good morning" SMS bhejte ho sirf ye check karne ke liye ki phone chal raha hai. Hamara **heartbeat** bhi wahi hai: har 15 minute me ek chhota BUY, phir ek chhota SELL. Isse pata chalta hai ki poora pipeline zinda hai.

## Safar, step by step
1. **signal-service** signal banata hai aur apni diary (`signal_log`) me likhta hai.
2. Wo signal Kafka topic `signal.generated` me jaata hai.
3. **core-service** use padhta hai, aur order banata hai. Risk-service pehle haan bolti hai, phir paper venue fill karta hai.
4. Order ka har badlav ek **outbox** table me likha jaata hai. Ek chhota worker har 1 second me use Kafka me bhej deta hai (`order.updated`, `fill.created`, `position.updated`).

## Outbox kyun?
Dukandar bill-book me entry karta hai, aur peon baad me parcel bhejta hai. Agar courier band hai, entry book me safe rehti hai; courier khulte hi parcel chala jaata hai. Outbox ke events nahi kho sakte.

## Manual ack kyun?
Consumer message ko "ho gaya" tabhi mark karta hai jab order sach me sambhal liya. Order banne se pehle crash hua to message dobara aayega aur order ban jaayega. Lekin agar order ban chuka (NEW) aur uske baad DB me gadbad hui, to dobara aane par purana order wahi atka milta hai; use dobara chalaya nahi jaata. Aisa order ERROR log me dikhta hai (`stuck in ...`) aur use haath se dekhna padta hai (ya slice 5 ka reconciliation sambhalega). Venue ko order bheja hi nahi gaya, isliye paisa safe hai, bas signal chhoot sakta hai.

## Duplicate order kaise rukta hai?
Har order ki key hoti hai `signal-<signal_id>`. Same signal dobara aaya to wahi purana order milta hai, naya nahi banta. Jaise ek hi parcel ka tracking number do baar scan karo, parcel do nahi hota.

## Kharab message ka kya?
Jo message padhne layak hi nahi, use log karke **skip** kar dete hain, taaki ek kharab chitthi poori line na roke.

## Topics
`kafka-init` start par 4 topics banata hai: `signal.generated`, `order.updated`, `fill.created`, `position.updated`. Har ek me 1 partition aur 7 din ka retention.

## Yaad rakho
- Demo ke liye interval sirf shell me badlo (`HEARTBEAT_INTERVAL_SECONDS=60`), `.env` me nahi.
- Kafka ~382 MiB leta hai (limit 512), us par nazar rakho.

Formal record: `../0026-heartbeat-and-kafka-signal-path.md`.
