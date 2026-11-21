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
| `services/brahma/` (core-service) | Exchange se baat, orders, positions (Java) |
| `services/maryada/` (risk-service) | Har order ko haan/na, kill-switch (Java) |
| `services/dvara/` (gateway-bff) | UI ke liye API aur live updates (Java) |
| `services/manthan/` (research-service) | Backtest, model training (Python) |
| `services/shankha/` (signal-service) | Live strategy chala ke signal deta hai (Python) |
| `ui/` | Angular trading screen |
| `infra/` | Docker compose, Postgres me har service ka alag schema |
| `docs/` | Knowledge aur release notes |

## Folder ke naam Sanskrit me kyun?
Har service ka folder ek Sanskrit naam hai jo uska kaam yaad dilata hai. Code aur docs me service ka role-naam (jaise `core-service`) bhi chalta rahega, dono ek hi cheez hain.

| Folder | Matlab | Kyun fit hai |
|---|---|---|
| `brahma` | Srishti karne wala | Core: orders aur positions yahin "bante" hain |
| `maryada` | Seema, limit | Risk: har order ko limit ke andar rakhta hai |
| `dvara` | Darwaza | Gateway: UI ka darwaza, sab yahin se andar aata hai |
| `manthan` | Samudra manthan, mathna | Research: data ko math ke model nikalta hai |
| `shankha` | Shankh, elaan | Signal: "trade ka mauka hai" ka elaan karta hai |

Example: kal kisi ne bola "risk-service me bug hai" — to folder `services/maryada/` kholo.

**Dhyan do:** Sanskrit naam **sirf folder** ke hain. Code, config, compose aur logs me role naam hi aata hai (`core-service`, `RiskServiceApplication`, `research_service`), taaki log ya error padhte hi samajh aaye ki kaun si service hai. Detail: `../0025-role-names-in-code.md`.

## Rule yaad rakho
- **Ek folder = ek service = ek database schema.** Doosre ke folder ka code ya table use nahi karna.
- Do services ko kuch share karna hai? To `contracts/` me shape likho, aur API ya Kafka event se data bhejo.
- Common code ka folder (`libs/`) tabhi banayenge jab sach me do jagah same code chahiye.

Formal record: `../0012-repository-layout.md`, naming: `../0013-service-folder-names.md`.
