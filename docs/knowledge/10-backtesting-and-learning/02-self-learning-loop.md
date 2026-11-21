---
title: Self-learning loop (safe tareeke se seekhna)
topic: backtesting-and-learning
level: advanced
---

# Self-learning loop

**Formal record:** `../0006-self-learning-loop.md`.

## 1. Idea
System apne results se **seekhta** hai, par seedha live paisa daav par lagake nahi. Pehle offline, phir jaanch, phir chhoti raashi.

**Example (naya driver):** Naya driver seedha highway par nahi jaata. Pehle simulator, phir khaali road par instructor ke saath, phir thodi traffic, tab akela highway.

## 2. Pipeline (8 steps)
| Step | Kya hota hai | Example |
|---|---|---|
| 1. Feature store | Data se "signals ke ingredients" banate hain, point-in-time sahi | "pichle 20 candles ka average" |
| 2. Training | Model ko purane data par sikhate hain (walk-forward) | Student padhta hai |
| 3. Model registry | Har model ka naam, version, data range, metrics, code hash | Marksheet par roll no. |
| 4. Evaluation | Naye data par test, **costs ke saath** | Class test |
| 5. Shadow mode | Model signal deta hai par **order nahi bhejta** | Cricket practice match ka commentary |
| 6. Champion vs Challenger | Naya model paper par chalta hai, purane model ke saath | Trial player vs main player |
| 7. Promotion gate | Tay thresholds pass hon tabhi aage, pehle **chhoti raashi** | Selection committee |
| 8. Drift monitoring | Live performance girne par model wapas shadow ya retire | Player form kharab to bench |

## 3. Shadow vs Paper
- **Shadow:** signal banata hai, kuch execute nahi. Sirf record.
- **Paper:** nakli exchange par nakli paise se pura trade chalta hai (fees, slippage ke saath).

## 4. Registry kyun zaroori?
Live me sirf **registry ka versioned model** chal sakta hai. "Hot-swap" yaani bina record ke model badal dena **mana** hai. Isse har trade ko yaad rakha jaa sakta hai: "ye trade model v7 ne diya tha".

## 5. Drift
**Matlab:** market badalne se model ki purani samajh ab kaam ki nahi.
**Example:** Barsaat ke mausam ke liye banaye hue raste ke rules garmi me alag lag sakte hain.
Hum do cheezein dekhte hain: live performance girna, aur input data ka swaroop badalna. Dono me se ek bhi hua to model auto-demote.

## 6. Kya seekhta hai aur kya nahi
**Seekhta hai:** signal models, regime detection (trending/ranging), capital allocator, execution (slippage) model.
**Kabhi nahi seekhta:** **risk engine aur kill-switch.** Limits insaan badalta hai.

## 7. Guardrails
- Simple models se shuru (jaise gradient boosting).
- Strict validation, chhoti capital.
- Saare experiments log (failed bhi).
- **Feedback loop ka khatra:** apne hi trades par seekhne wala system apni galti ko aur pakka kar sakta hai. Exploration limited, capital capped.

## Yaad rakhne wali baatein
- Offline seekho, live me freeze model.
- Raasta: train → evaluate → shadow → paper → chhoti live raashi.
- Registry ke bina model live nahi.
- Risk engine kabhi nahi seekhta.
