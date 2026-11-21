---
title: Service, monolith aur microservices
topic: architecture-basics
level: beginner
---

# Service, monolith aur microservices

## 1. Service kya hoti hai?
Ek chhota alag program jo **ek kaam acche se** karta hai aur chalta rehta hai.

**Example (restaurant):** Restaurant me kaam baante hote hain: rasoiya khana banata hai, waiter order leta hai, cashier bill banata hai, manager sab dekhta hai. Sab ek hi insaan kare to gadbad. Services aise hi alag-alag "staff" hain.

## 2. Monolith vs Microservices
| | Monolith | Microservices |
|---|---|---|
| Kya hai | Poora system **ek hi bada program** | System **bahut saare chhote programs** me bata hua |
| Example | Ek aadmi ki dukaan: wahi bechta, wahi hisaab, wahi saaf karta | Mall: har dukaan alag, alag owner |
| Fayda | Sab ek jagah, simple, tez shuru karna | Ek ka kharab hona doosre ko nahi girata, alag-alag scale |
| Nuksaan | Ek jagah bug to sab girta hai, bada hone par samajhna mushkil | Zyada jod-tod, network ki dikkat, monitoring mushkil |

## 3. Hamara raasta: "Modular monolith + kuch alag processes"
Hum **sab kuch microservices nahi** banate. Alag process **sirf wahan** jahan sach me wajah ho: safety, alag language ya bhaari compute.

| Service | Language | Alag kyun? |
|---|---|---|
| `core-service` | Java (Spring Boot) | Exchange connectors, market data, orders, positions. Andar se "modular" (alag modules, par ek program) |
| `risk-service` | Java (Spring Boot) | **Safety:** core me bug aaye tab bhi risk engine zinda rahe aur order roke |
| `research-service` | Python (FastAPI) | Bhaari kaam: backtest, model training, ML libraries |
| `signal-service` | Python (FastAPI) | Strategy chalana aur model se signal nikalna |
| `gateway-bff` | Java (Spring Boot) | UI ke liye API, live WebSocket, alerts, scheduler |
| `ui` | Angular | Trading terminal ki screen |

**Java kyun aur Python kyun?**
- **Java (Spring Boot):** paisa aur orders ka kaam. Strongly typed, stable, concurrency me acchi, bugs compile time par pakde jaate hain.
- **Python (FastAPI):** ML/data science ki saari libraries Python me hain. Isliye research aur signal Python me.
- **Node/NestJS nahi:** project ka faisla hai ki backend sirf Java aur Python (dekho `../0011-backend-java-and-python-only.md`).

## 4. BFF kya hota hai? (`gateway-bff`)
**BFF = Backend For Frontend.** Ye UI ka "personal assistant" hai. UI ko 4 alag services se baat nahi karni padti, wo sirf BFF se baat karta hai. BFF baaki services se data lekar UI ke liye thik format me deta hai.

**Example:** Hotel me aap sirf **receptionist** se baat karte ho. Receptionist kitchen, housekeeping, laundry se khud baat karke aapko jawab deta hai. Receptionist = BFF.

**Zaroori rule:** BFF order/fee/position ka hisaab **nahi** karta. Wo sirf dikhata hai aur user ke actions aage core/risk ko bhejta hai.

## 5. Ek chhota flow
1. `core-service` exchange se price data leta hai.
2. `signal-service` data dekhkar bolta hai "BTC kharido".
3. `core-service` order banata hai, `risk-service` se puchta hai "theek hai?".
4. Risk "haan" bole to order exchange ko jaata hai.
5. `gateway-bff` UI ko live dikhata hai.

## Yaad rakhne wali baatein
- Service = ek kaam ka chhota program.
- Hum modular monolith hain, alag process sirf zaroorat par.
- Backend: Java (paisa/orders/risk/gateway) + Python (ML/research/signal).
- BFF = UI ka assistant, business logic nahi.
