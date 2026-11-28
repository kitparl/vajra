---
title: SMA cross strategy aur hamara backtester
topic: backtesting-and-learning
level: intermediate
---

# SMA cross strategy aur hamara backtester

## Chhota example
Ek dukaan ki roz ki bikri ka do tarah ka average lo:
- **3 din ka average** (fast): jaldi badalta hai.
- **8 din ka average** (slow): dheere badalta hai.

Jab fast average slow ke **upar** chala jaye, to lagta hai bikri badh rahi hai, maal kharido (BUY). Jab fast wapas **neeche** aa jaye, to maal bech do (SELL). Yahi **SMA crossover** hai.

## Hamare project me
- Strategy sirf 1-minute ke **band ho chuke** candles dekhti hai. Default 20 aur 60 minute ka average hai.
- Ye sirf **long ya flat** rehti hai, 1 contract ke saath:
  - BUY tabhi karti hai jab position 0 ho.
  - SELL tabhi karti hai jab position 0 se zyada ho.
  - Short position par kuch nahi karti.
- **Ek hi code, do jagah:** `vajra_strategies` ka `SmaCross.decide()` live (signal-service) aur backtest (research-service), dono me chalta hai. Isliye "backtest me alag, live me alag" wala dhokha nahi ho sakta.

## Lookahead se bachne ke niyam
1. **Signal aaya candle i par, to fill candle i+1 ke open par.** Jis candle par faisla liya, usi ke close par kharidna future dekhna hota.
2. **Sabse naya candle kabhi istemal nahi hota.** Core abhi chal rahe minute ko adhoora save karta hai, aur agle sync me sahi karta hai. Isliye sabse naya row shayad final nahi hai, aur uske pehle wale sab final hain.
3. **Bade gap par faisla nahi.** Agar zaroori candles `slow + 5` minute se zyada me faile hain (jaise outage ke baad), to `decide()` kuch nahi karta. Ye niyam bhi `decide()` ke andar hai, isliye live aur backtest ek jaisa skip karte hain.

## Kharcha (costs)
- Fee: har fill par `fee × size × contract_value × price`. Testnet par taker fee 0.05% hai.
- Slippage: 2 bps humesha hamare khilaf, yaani kharidne par thoda mehenga aur bechne par thoda sasta.
- **Funding abhi shamil nahi** hai, aur har result me `funding_included=false` likha hota hai.

## Pehla result (BTCUSD, 90 din)
- 1526 trades hue aur net −272.73 USD rahe, jisme fees 117.77 thi. Win rate 15.8% raha.
- **Matlab:** 1-minute par 20/60 SMA bahut baar ulta-pulta hota hai, aur fees hi sab kha jati hain. Is phase ka kaam raasta saabit karna tha, paisa banana nahi.

## Command
```
curl -X POST localhost:8092/api/v1/backtests -H "Content-Type: application/json" \
  -d '{"symbol":"BTCUSD","start":"2026-08-30T00:00:00Z","end":"2026-11-28T00:00:00Z"}'
```

Formal record: `../0028-sma-cross-strategy-and-backtester.md`.
