---
title: LLM kahan use karein aur kahan nahi
topic: ai-llm-usage
level: beginner
---

# LLM kahan use karein aur kahan nahi

## 1. LLM kya hai?
Bada AI model jo **text samajhta aur likhta** hai (jaise Claude). Hum ise **external API** se use karte hain, apna LLM train nahi karte.

## 2. Yahan madad karta hai (`research-service` me)
| Kaam | Example |
|---|---|
| **News tagging** | Khabar aayi "Exchange par hack". LLM tag lagata hai: `bearish`, `exchange-risk` |
| **Post-trade analysis** | "Is trade me loss kyun hua?" ka summary |
| **Research draft** | "Is strategy ka idea likho" (sirf **draft**) |

**Example:** LLM ek **intern** jaisa hai: padh ke summary de sakta hai, par company ke paise ka faisla intern par nahi chhodte.

## 3. Yahan KABHI nahi
- Order place karna ya size tay karna.
- Risk limits badalna.
- Kill-switch ko control karna.
Hamara non-goal bhi yahi hai: **"Fully LLM-driven order decisions"** nahi.

## 4. LLM ki khaas galti: Lookahead leakage
LLM ko training me **baad ke events** pata hote hain.

**Example:** Aap LLM se 2022 ki news par sentiment nikalwate ho. LLM ko pata hai ki baad me kya hua, to uska sentiment galti se "future ke hisaab se sahi" aa jaata hai. Backtest bahut acha dikhega, live me nahi.

**Bachav:** LLM-based features ko **forward test** (paper trading me live news par) se verify karo, sirf purane backtest par bharosa mat karo.

## 5. LLM ka output bhi gate se guzarta hai
LLM ne koi strategy idea diya? Wo bhi **backtest + promotion gate** (shadow, paper, small live) se guzarega, kisi bhi doosre idea ki tarah.

## 6. Dhyaan
- API keys secret manager me.
- Kharch par limit, logs me kya bheja gaya rakho.
- Sensitive data (exchange keys, account balances) prompt me **mat** bhejo.

## Yaad rakhne wali baatein
- LLM = research assistant, trader nahi.
- Order/risk/kill-switch par LLM ka haath nahi.
- Lookahead leakage se savdhan, forward test karo.
