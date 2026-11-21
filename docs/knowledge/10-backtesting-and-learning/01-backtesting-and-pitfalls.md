---
title: Backtesting aur uski galtiyan
topic: backtesting-and-learning
level: intermediate
---

# Backtesting aur uski galtiyan

## 1. Backtest kya hai?
Apni strategy ko **purane data par chala ke dekhna** ki agar tab chalate to kya hota.

**Example:** Match ka purana scorecard dekhkar "agar main captain hota aur ye batting order rakhta" ka hisaab lagana.

## 2. Backtest kyun zaroori
Seedha live paise lagane se pehle pata chale ki strategy me dum hai ya nahi.

## 3. Sabse bade jaal (pitfalls)

### a) Lookahead bias (future jhaank lena)
**Matlab:** aise data ka use jo us waqt **available hi nahi tha**.

**Example:** Exam ke answers dekh kar exam dena. Marks acche aayenge, par asli exam me fail.

Trading me: 10:00 baje ka decision lena par 10:05 ki candle ka close use kar liya. Backtest shaandaar, live me bekaar.

**Bachav:** features "point-in-time" banao, yaani har decision ke waqt sirf **tab tak ka data** use ho.

### b) Overfitting (ratta marna)
**Matlab:** strategy ko purane data ke "yaad" kar liya, par naye data par nahi chalti.

**Example:** Ek student ne pichle saal ke paper ke answers ratt liye. Isi saal naye sawaal aaye to fail.

Kaise hota hai: 1000 parameter combinations try kiye, jo sabse acchi nikli wahi chuni. Wo shayad sirf **kismat** thi.

**Bachav:**
- Data ko **train** aur **test** me baanto. Test par sirf ek baar jaanch.
- **Walk-forward** validation (neeche).
- Saare experiments ka **record** rakho, failed wale bhi.
- Simple model pehle.

### c) Costs bhool jaana
Fees, spread, slippage, funding. Inhe chhodne se backtest jhootha profit dikhata hai.

**Example:** Ek trade me ₹100 kamaye par ₹30 fees. 100 trades me ₹3,000 fees udd gaye jo backtest me nahi dikhe.

### d) Liquidation bhool jaana
Leverage me rate recover hone se pehle aapki position liquidate ho sakti hai. Backtest me **margin** bhi simulate karo, sirf profit/loss nahi.

### e) Survivorship bias
Sirf wahi coins test kiye jo aaj zinda aur popular hain. Jo mar gaye unhe bhool gaye, to result acha dikhta hai.

### f) Kam trades
10 trades ka result **kismat** bhi ho sakta hai. Zyada trades chahiye. Result "range" me batao, ek number nahi.

### g) Regime change
Market badalta hai. Jo model pichle quarter me chala wo is quarter me nahi bhi chal sakta.

## 4. Walk-forward validation
Data ko **aage ki taraf sarkate hue** train aur test karo.
```
Train: Jan-Jun  -> Test: Jul
Train: Feb-Jul  -> Test: Aug
Train: Mar-Aug  -> Test: Sep
...
```
**Example:** Student har mahine ke test ke baad naya syllabus padhta hai aur agle mahine ke test me dikhata hai. Ye asli duniya jaisa hai.

## 5. Same code backtest aur live me
Hamara rule: **strategy ka code ek hi hai**, sirf data ka source badalta hai (purana vs live). Alag code likha to "backtest me chalta tha" wale bugs aate hain.

## 6. Key metrics (naap)
- **Sharpe ratio:** risk ke hisaab se kitna return mila. Zyada acha.
- **Max drawdown:** sabse bada girawat. Kam acha.
- **Hit rate:** kitne % trades jeete.
- **Turnover:** kitni baar trade kiya (zyada = zyada fees).

## Yaad rakhne wali baatein
- Lookahead = future jhaank lena. Overfitting = ratta marna.
- Fees, slippage, funding, liquidation sab jodo.
- Walk-forward = aage sarakte hue test.
- Backtest aur live ka code ek hi.
