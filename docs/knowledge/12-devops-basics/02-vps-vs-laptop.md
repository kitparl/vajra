---
title: VPS par kya chalega, laptop par kya
topic: devops-basics
level: beginner
---

# VPS par kya chalega, laptop par kya

## Chhota example
Ek restaurant socho. Kitchen chhoti hai, bas 4 chulhe hain.
Roz ka khana (orders) yahin banna chahiye. Lekin nayi recipe ki practice, jisme ghanton chulhe lagte hain, ghar par karte hain. Recipe pakki ho jaaye tab kitchen me laate hain.
Agar practice bhi restaurant ki kitchen me karoge to customer ka order late hoga, ya chulha hi band ho jayega.

## Hamare project me
- **VPS (restaurant ki kitchen):** sirf 4 GB RAM. Yahan live trading chalegi: `core`, `risk`, `signal`, `gateway`, UI, Postgres, Kafka, Redis.
- **Laptop (ghar ka kitchen):** `research-service`: backtest aur model training. Ye bahut RAM khaata hai.
- Model train ho jaaye aur gate (shadow, paper, chhota live) pass kare, tabhi VPS par jaata hai.

## Kyun zaroori hai
RAM khatam hui to Linux koi bhi process kill kar deta hai. Agar wo `risk-service` hua aur us waqt order khule the, to paisa bina suraksha ke reh jayega.
Isliye har container ki memory limit pehle se tay hai.

Formal record: `../0014-deployment-split-vps-and-laptop.md`.

## Update: LifeOS bhi isi VPS par hai
Fridge me pehle se LifeOS ka khana rakha hai. Isliye vajra ke liye 4 GB poori nahi milegi. LifeOS ka asli memory use naap kar budget dobara banega (`../0019-lessons-from-lifeos.md`).
