---
title: Repo ka folder structure
topic: architecture-basics
level: beginner
---

# Repo ka folder structure

## Chhota example
Ek ghar socho. Har kamre ka apna kaam hai: kitchen me khana, bedroom me sona. Kitchen ka saaman bedroom me nahi rakhte.
Hamara repo bhi aisa hi ghar hai, aur har service ek kamra.

## Hamare repo ke kamre
| Folder | Kya rakhta hai |
|---|---|
| `contracts/` | Sabki common "bhasha" (Protobuf). Event aur API ka shape sirf yahin likha jaata hai. |
| `services/core-service/` | Exchange se baat, orders, positions (Java) |
| `services/risk-service/` | Har order ko haan/na, kill-switch (Java) |
| `services/gateway-bff/` | UI ke liye API aur live updates (Java) |
| `services/research-service/` | Backtest, model training (Python) |
| `services/signal-service/` | Live strategy chala ke signal deta hai (Python) |
| `ui/` | Angular trading screen |
| `infra/` | Docker compose, Postgres me har service ka alag schema |
| `docs/` | Knowledge aur release notes |

## Rule yaad rakho
- **Ek folder = ek service = ek database schema.** Doosre ke folder ka code ya table use nahi karna.
- Do services ko kuch share karna hai? To `contracts/` me shape likho, aur API ya Kafka event se data bhejo.
- Common code ka folder (`libs/`) tabhi banayenge jab sach me do jagah same code chahiye.

Formal record: `../0012-repository-layout.md`.
