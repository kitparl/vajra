---
title: Paper order ka safar (NEW se FILLED tak)
topic: order-lifecycle
level: beginner
---

# Paper order ka safar

## Paper trading kya hai?
**Example:** Cricket practice net. Asli match nahi hai, par bowler aur pitch asli jaise hain. Isi tarah paper trading me **asli paisa nahi lagta**, par price asli (Delta testnet ke live quotes) use hote hain.

Vajra me abhi sirf paper venue chalta hai. Live trading band hai.

## Order ka safar
Courier parcel jaisa: Booked, Picked up, In transit, Delivered.
```
NEW -> APPROVED -> SENT -> ACKED -> FILLED
```
- NEW: order ban gaya
- APPROVED: risk gate ne haan bola
- SENT / ACKED: paper venue ko gaya aur usne maan liya
- FILLED: poora ho gaya

Market order turant "touch" price par fill hota hai: BUY ask par, SELL bid par. Limit order agar market se door hai to **ACKED me ruka rehta hai** (resting) jab tak price wahan na aaye, ya aap cancel kar do (CANCELLED).

Price 10 second se purana ho to `STALE_PRICE`: purane rate par trade nahi karte.

## Idempotency: "ek hi button do baar daba diya"
**Example:** Lift ka button aapne do baar daba diya, lift fir bhi ek hi baar aati hai.

Har order ke saath `Idempotency-Key` bhejte hain (jaise `demo-1`).
- Wahi key + wahi order dobara: purana order wapas (HTTP 200), naya nahi banta.
- Wahi key + alag size/price: 409 error, kyunki ye galti ho sakti hai.

## PnL ka chhota hisaab
**Example:** Aapne 50000 par 2 contract kharide. Ab price 50500 hai. Contract value 0.001 hai.
Unrealized PnL = 2 x 0.001 x (50500 - 50000) = **1.000** (abhi becha nahi, isliye "unrealized").

Becha to wo **realized** ho jaata hai. Fees alag katti hain.
`net = realized - fees + unrealized`.

Flip example: long 2 @ 50000, phir 5 becha @ 51000. Pehle 2 band hue (realized 2), baki 3 se **short 3 @ 51000** ban gaya.

Agar fresh price nahi hai to unrealized `null` dikhta hai, andaza nahi lagate.

## Vajra me kahan
`OrderService`, `OpenOrderChecker` (was `PaperMatcher`), `PositionMath` in `services/brahma`. Detail: `docs/knowledge/0023-oms-and-paper-trading.md`.
