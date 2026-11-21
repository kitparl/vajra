---
title: Risk basics
topic: risk-management
level: beginner
---

# Risk basics

## 1. Stop loss
**Matlab:** pehle se tay kar lena ki "itna nuksaan hua to main bahar".

**Example:** Aapne 50,000 pe BTC kharida. Aapne tay kiya "49,500 pe stop loss". Rate 49,500 tak gira to position khud band. Aapka loss chhota raha. Agar stop loss nahi lagaya aur rate 40,000 gaya, to bhaari nuksaan.

## 2. Position size (kitna lagana hai)
Sabse zaroori sawal: **ek trade me kitna paisa daav pe lagaun?**

**1% rule (common thumb rule):** ek trade me total paise ka **zyada se zyada 1% hi risk** karo.

**Example:**
- Account ₹1,00,000.
- 1% risk = ₹1,000 max loss per trade.
- Entry 50,000, stop loss 49,500 (500 ka fark, yaani 1%).
- Position size = ₹1,000 ÷ 1% = **₹1,00,000** ka trade (account ke barabar, yaani 1x leverage).
- Agar stop loss 49,000 hota (2% fark), to size = ₹1,000 ÷ 2% = ₹50,000. Stop jitna door, size utna chhota.

Aise 10 baar lagatar haaro bhi to account ka sirf ~10% gaya, khatam nahi hua.

## 3. Drawdown
**Matlab:** account ke sabse upar ke point se neeche gire hue paise ka %.

**Example:** Account ₹1,00,000 se ₹1,20,000 hua, phir gir ke ₹90,000 hua. Peak se girawat = ₹30,000 ÷ ₹1,20,000 = **25% drawdown.**

**Dhyaan rakho (ulti ginti):** 50% gire to wapas pehle jaisa hone ke liye **100% badhna** padta hai. Isliye bade drawdown se bachna zaroori hai.

| Drawdown | Wapas aane ke liye chahiye |
|---|---|
| 10% | 11% |
| 25% | 33% |
| 50% | 100% |
| 80% | 400% |

## 4. Daily loss limit
Din ka max loss tay hota hai, jaise 3%. Wo cross hua to us din aur trade band. Isse "badla lene wali trading" (revenge trading) ruki rehti hai.

## 5. Risk-reward ratio
Ek trade me aap kitna risk lete ho vs kitna kamane ki ummeed hai.
**Example:** Risk ₹500, target profit ₹1,000 → **1:2**. Is ratio me aap sirf 40% trades jeet kar bhi profit me reh sakte ho.

Check: 10 trades, 4 jeete (4 × ₹1,000 = ₹4,000), 6 haare (6 × ₹500 = ₹3,000). Net = **+₹1,000**.

## 6. Exposure aur concentration
- **Exposure** = abhi kul kitna paisa daav pe laga hai.
- **Concentration** = saare paise ek hi coin ya ek hi strategy me to nahi?
Ek hi jagah sab lagana = ek bura din sab khatam kar sakta hai. Hamare risk-service me per-strategy aur per-exchange cap hote hain.

## Hamare project me
Ye saari limits **config** hain jo insaan badalta hai. Koi ML model inhe nahi badal sakta (dekho `02-risk-engine-and-kill-switch.md`).

## Yaad rakhne wali baatein
- Pehle bachao, phir kamao.
- 1% rule: ek trade me account ka sirf ~1% risk.
- Bada drawdown wapas laana bahut mushkil hai.
