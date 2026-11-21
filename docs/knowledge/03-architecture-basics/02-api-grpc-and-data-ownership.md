---
title: API, REST/gRPC, sync/async, data ownership
topic: architecture-basics
level: intermediate
---

# API, REST/gRPC, sync/async aur data ownership

## 1. API kya hai?
Ek service ka "counter" jaha doosri service apni request dalti hai aur jawab leti hai.
**Example:** Restaurant ka waiter = API. Aap kitchen me ghuskar khana nahi banate, waiter ko order dete ho aur wo khana laata hai.

## 2. REST vs gRPC
| | REST | gRPC |
|---|---|---|
| Kaisa dikhta hai | `GET /orders/123` (JSON text) | Function call jaisa, data binary (Protobuf) |
| Example | WhatsApp text message | Fast phone call me direct baat |
| Kab | UI aur bahari tools se baat | Services ke beech tez aur strict baat |

Hamare project me: UI ↔ `gateway-bff` REST + WebSocket. Services ke beech zyadatar gRPC/REST + Kafka.

## 3. Contract (`contracts/` folder)
Do services ko pehle se tay karna hota hai "data kaisa dikhega". Ye likha jaata hai **Protobuf** file me (`contracts/` folder). Phir isse Java aur Python (aur UI) ka code automatic bante hain.

**Example:** Do deshon me treaty. Dono sahmat hon ki form kaisa hoga, to bhasha alag ho tab bhi samajh jaate hain.

## 4. Sync vs Async
- **Sync (turant jawab):** call karo, jawab aane tak ruko. **Example:** phone call.
- **Async (baad me jawab):** message bhejo, aage badho, jawab baad me. **Example:** WhatsApp message / email.

Hamare project me:
- **Sync:** `core-service` ➜ `risk-service` "kya ye order theek hai?" Jawab ke bina aage nahi badhte. Jawab na aaye to **reject**.
- **Async (Kafka):** market data, signals, order/fill updates, alerts. (dekho `../07-kafka-and-events/`)

## 5. Data ownership (sabse zaroori rule)
**Har table ka ek hi maalik.** Ek service dusri service ke table ko **na padhe, na likhe**.

**Example (ghar ki almari):** Har bande ki apni almari hai. Doosre ki almari me haath daalna mana hai. Kuch chahiye to us bande se maango.

Hamare Postgres me ek cluster, **har service ka apna schema**:
| Schema | Maalik | Kya rakhta hai |
|---|---|---|
| `core` | core-service | orders, fills, positions, market data, audit |
| `risk` | risk-service | limits, decisions, kill-switch state |
| `research` | research-service | experiments, backtests, model registry |
| `signal` | signal-service | strategies, signals |
| `gateway` | gateway-bff | users, watchlists, alert rules |

**Rules:**
- Koi cross-schema join nahi.
- Koi cross-schema foreign key nahi.
- Data chahiye to API call karo ya Kafka event suno aur apna chhota "read copy" rakho.

**Ye rule kyun?** Agar `signal-service` seedha `core.orders` table padhne lage aur kal core us table ka naam badal de, to signal-service toot jayegi. Ownership se har service **akeli badal sakti hai**.

## Yaad rakhne wali baatein
- API = counter jahan se baat hoti hai.
- Contracts Protobuf me, ek jagah.
- Risk check sync, baaki zyadatar async.
- Har table ka ek hi maalik.
