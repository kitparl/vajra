---
title: Spot, futures, leverage, liquidation
topic: trading-basics
level: beginner
---

# Spot, futures, leverage, liquidation

## 1. Spot trading (seedha kharidna)
**Matlab:** aap asli coin kharidte ho aur apne wallet me rakhte ho.

**Example:** Aapne 1 BTC ₹50 lakh me kharida. Rate ₹55 lakh hua, aapne becha. **Profit = ₹5 lakh.**
Rate ₹45 lakh hua to loss = ₹5 lakh (jab tak aap bechte nahi, ye "paper loss" hai).

Simple rule: **spot me aap apne paise se zyada kabhi nahi haaroge.** Maximum loss = jitna lagaya.

## 2. Futures / Derivatives (rate pe sattebaazi)
**Matlab:** aap coin nahi kharidte, aap sirf **rate ke upar-neeche jaane** pe bet lagate ho. Isko "contract" kehte hain.

**Example (chai ki dukaan wali bet):**
Aapka dost bolta hai "agle mahine chai ₹10 se ₹15 ho jayegi". Aap bolte ho "nahi, ₹8 ho jayegi". Dono ₹1000 ki bet lagate ho. Jo sahi, wo jeetega. Chai kisi ne kharidi nahi, sirf bet hui. Futures aisa hi hai.

### Long aur Short
- **Long** = "rate **badhega**" pe bet. Rate badha to profit.
- **Short** = "rate **girega**" pe bet. Rate gira to profit.

Spot me sirf badhne pe kamai hoti hai. Futures me girne pe bhi kamai ho sakti hai (short se).

### Perpetual contract
Aisa futures contract jiski **expiry date nahi hoti**. Crypto me sabse common. Delta Exchange pe ye mil jata hai.

## 3. Leverage (udhaar ki taakat)
**Matlab:** aap apne paise se **kai guna bada** trade kar sakte ho. Exchange baaki paisa "udhaar" deta hai.

**Example:**
- Aapke paas ₹10,000 hain.
- 10x leverage lagaya → aap ₹1,00,000 ka trade kar rahe ho.
- Rate **1% badha** → ₹1,00,000 ka 1% = ₹1,000 profit. Aapke ₹10,000 pe ye **10% profit** hai.
- Rate **1% gira** → ₹1,000 loss = aapke paise ka **10% loss**.

Yaani leverage ek **magnifying glass** hai. Profit bhi bada, loss bhi bada.

| Leverage | Rate 1% girne par aapke paise ka loss |
|---|---|
| 1x (spot jaisa) | 1% |
| 5x | 5% |
| 10x | 10% |
| 50x | 50% |
| 100x | 100% (sab khatam) |

## 4. Margin
**Margin** = wo paisa jo aap "security deposit" ki tarah exchange ke paas rakhte ho taaki leverage wala trade khul sake.
Upar ke example me ₹10,000 aapka margin hai.

## 5. Liquidation (sabse khatarnak cheez)
**Matlab:** jab loss itna bada ho jaye ki aapka margin khatam hone lage, exchange **zabardasti aapki position band kar deti hai**. Aapka margin gaya.

**Example:**
- ₹10,000 margin, 10x leverage, long position.
- Rate lagbhag **10% gira** to aapka margin khatam. Exchange position band kar degi.
- Baad me rate wapas badh bhi jaye, aapko kuch nahi milega, kyunki position to pehle hi band ho chuki.

**Isliye hamare project me:** leverage ki **hard limit** hai (risk-service lagata hai). Koi model ya bug 100x leverage nahi laga sakta.

## 6. Funding rate (perpetual ka chhota kharcha)
Perpetual contract me expiry nahi hoti, to rate ko asli rate ke paas rakhne ke liye longs aur shorts ek doosre ko **thodi-thodi der me chhoti fees** dete hain. Ise funding kehte hain.
- Funding positive: longs, shorts ko dete hain.
- Funding negative: shorts, longs ko dete hain.

**Yaad rakho:** position lambi der rakhi to funding ka kharcha bhi profit se katega. Backtest me ye jodna zaroori hai (dekho `../10-backtesting-and-learning/`).

## Yaad rakhne wali baatein
- Spot = asli coin, loss limited.
- Futures = sirf rate pe bet, long/short dono ho sakte hain.
- Leverage = profit aur loss dono ko multiply karta hai.
- Liquidation = margin khatam, position zabardasti band.
- Hamara system **safe leverage limit** ke andar hi trade karta hai.
