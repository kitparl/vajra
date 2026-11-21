---
title: Contracts aur EventEnvelope
topic: kafka-and-events
level: intermediate
---

# Contracts aur EventEnvelope

## Chhota example
Courier company har parcel ko ek **standard box** me daalti hai jis par label hota hai: tracking id, kab bheja, kisne bheja. Andar kuch bhi ho sakta hai.
Hamara `EventEnvelope` wahi box hai:

| Label | Matlab |
|---|---|
| `event_id` | Tracking number (UUID) — duplicate pehchanne ke liye |
| `event_type` | Kya hua, jaise `order.updated` |
| `occurred_at` | Kab hua |
| `producer` | Kis service ne bheja |
| `payload` | Andar ka asli saaman |

Fayda: audit log me har event ek hi tarah dikhega, chahe andar kuch bhi ho.

## Contract kya hai?
Do services ke beech **pakka agreement** ki message ka shape kya hoga. Ye sirf `contracts/proto/` me likha jaata hai (Protobuf). Java aur Python dono iska code **khud generate** karte hain (`mvn verify`, `uv sync`), haath se nahi likhte, git me commit nahi karte.

## Contract todna mana hai
Agar kisi ne field hata di ya number badal diya, purani service message padh nahi paayegi. Isliye CI me `buf breaking` chalta hai: aisa badlav PR me hi fail ho jaata hai.

Formal record: `../0017-contracts-pipeline.md`.
