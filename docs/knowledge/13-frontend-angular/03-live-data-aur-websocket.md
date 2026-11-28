---
title: Live data, WebSocket aur reconnect
topic: frontend-angular
level: intermediate
---

# Live data, WebSocket aur reconnect

## Chhota example
Aap cricket match dekh rahe ho:
- **Snapshot** ek baar scorecard dekhna hai: "abhi 120/3". Ye REST call hai.
- **WebSocket** radio commentary hai: "chauka!", "wicket!". Har ball par khabar apne aap aati hai, baar-baar puchhna nahi padta.
- Radio thodi der band ho jaye to beech ki balls chhoot jaati hain. Isliye radio wapas aate hi **scorecard dobara dekhte ho**, taaki koi baat chhoot na jaye.

## Hamare project me
- **Pehle snapshot, phir live.**
  - Har screen pehle REST se data laati hai (`/quotes`, `/orders`, `/positions`).
  - Phir gateway ke `/ws` se live messages aate hain: `hello`, `quote`, `order`, `fill`, `position` aur `risk`.
- **Socket khulte hi snapshot dobara.** Socket jab bhi khule, pehli baar bhi, har screen apna snapshot dobara leti hai. Warna snapshot aur socket ke beech koi order FILLED ho jaye to wo hamesha NEW hi dikhta.
- **Reconnect:**
  - Socket toote to 1, 2, 5 aur 10 second ruk kar dobara try hota hai (10 se zyada nahi). Top bar "reconnecting" dikhata hai.
  - Code **4401** ka matlab hai session khatam (logout ya timeout). Tab seedha login page khulta hai.
- **Ulta kram:** do updates kabhi ulte kram me aa sakte hain. Isliye UI wahi rakhti hai jiska `updatedAt` naya ho, aur purana update chhod deti hai.
- **Paisa sirf server ginta hai.**
  - Mark, unrealized P&L aur net P&L core banata hai. UI unhe waise hi dikhati hai, jaise `"0.00012345"`.
  - Market chalta hai to UI har 2 second me zyada se zyada ek baar `/pnl` aur `/positions` dobara mangti hai.
  - Sirf "24 ghante me kitna % badla" UI khud nikalti hai, aur wo bhi sirf dikhane ke liye.
- **Rang ke saath nishaan bhi:**
  - ▲/▼, "stale" aur "HALTED" text me likhe hote hain, taaki rang na dikhne par bhi baat samajh aaye.
  - Badalne wala cell 600 ms tak chamakta hai.
- **Kuch band ho to:** core band ho to sirf us panel me "core unavailable" likha aata hai. Baaki screen chalti rehti hai, aur mode badge `UNKNOWN` laal rang me dikhata hai.

## Gateway ki taraf se
- **Socket kaun khol sakta hai:** sirf login session wala, aur sirf hamari apni site se. `Origin` ka `GATEWAY_UI_ORIGIN` se bilkul match hona zaroori hai, warna 403.
- **Atka hua phone baakiyon ko nahi rokta.** Har message alag virtual thread se jaata hai. Jo phone 5 second me message na le, ya jiska 256 KiB buffer bhar jaye, sirf wahi band hota hai.
- **Polling:** quotes har 1 second aur risk har 2 second me aate hain, aur sirf tab jab koi screen khuli ho.

Formal record: `../0031-read-screens-and-live-stream.md`.
