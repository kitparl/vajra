---
title: Order types, candles, bid/ask, slippage
topic: trading-basics
level: beginner
---

# Order types, candles, bid/ask, slippage

## 1. Order kya hota hai?
Exchange ko diya gaya **hukm**: "itna kharido" ya "itna becho". Order ke saath ye batana padta hai: kaunsa instrument (BTCUSD), buy ya sell, kitna quantity, aur kis tarah ka order.

## 2. Bid, Ask, Spread
Sabzi mandi ka example: sabzi wala bolta hai "aloo ₹30 kilo me bechunga" (**ask**), aur grahak bolta hai "₹28 me loonga" (**bid**).
- **Bid** = sabse zyada rate jis par koi **kharidna** chahta hai.
- **Ask** = sabse kam rate jis par koi **bechna** chahta hai.
- **Spread** = ask − bid. Ye ek chhota chhupa hua kharcha hai.

Example: bid 50,000, ask 50,010 → spread 10. Aap abhi kharidoge to 50,010 pe, aur turant bechoge to 50,000 pe. Yaani turant ₹10 ka loss, sirf spread ki wajah se.

## 3. Order ke prakaar
| Order | Matlab | Example | Fayda / nuksaan |
|---|---|---|---|
| **Market** | "Abhi jo rate chal raha hai usi pe kharid do" | Bus pakadni hai, kiraya jo ho, abhi chadhna hai | Turant fill hota hai, par rate thoda kharab mil sakta hai (slippage) |
| **Limit** | "Sirf is rate ya usse behtar pe kharido" | "Aloo ₹25 me mile tabhi loonga" | Rate control me, par fill na bhi ho sakta hai |
| **Stop (stop-loss)** | "Rate is level tak gira to mujhe bahar nikaal do" | "Agar ₹48,000 tak gira to bech dena, aur nuksaan nahi chahiye" | Loss ko limit karta hai |
| **Take-profit** | "Is rate tak chadha to bech ke profit le lo" | "₹55,000 aate hi bech do" | Profit lock karta hai |

## 4. Slippage
**Matlab:** aapne socha 50,000 pe milega, par asal me 50,030 pe fill hua. Ye farak slippage hai.
Market order me aur kam liquidity (kam log trade kar rahe ho) me zyada hota hai.

**Example:** Aapko 10 BTC kharidne hain, par 50,000 pe sirf 2 BTC bikne ko hain. Baaki 8 BTC agle mehange rates pe milenge. Average rate upar chala gaya. Ye slippage hai.

## 5. Fees
Har trade pe exchange fees leti hai (maker/taker fee). Chhoti lagti hai, par bahut trades karne par bada kharcha ban jaati hai.
- **Maker:** aap limit order lagake book me "intezaar" karte ho (liquidity dete ho). Fees kam.
- **Taker:** aap turant market order se kisi ka order le lete ho. Fees zyada.

## 6. Candle (candlestick) kaise padhein
Rate ke chart me har ek "daba" ek **time period** (jaise 1 minute ya 1 ghanta) ka saar hai. Isme 4 cheezein hoti hain, **OHLC**:
- **O**pen: period ke shuru me rate
- **H**igh: period me sabse upar gaya rate
- **L**ow: period me sabse neeche gaya rate
- **C**lose: period ke end me rate

Example (1 ghante ki candle): Open 50,000, High 50,400, Low 49,900, Close 50,300. Matlab rate 50,000 se shuru hua, 50,400 tak gaya, 49,900 tak gira, aur 50,300 pe band hua. Close > Open hai, to ye **hari (bullish)** candle hai.

## 7. Hamare project me kahan use hota hai
- Connector candles aur ticks laata hai (dekho `../09-exchange-connector/`).
- `signal-service` candles se signal banata hai.
- Order types `core-service` ke order state machine me handle hote hain (dekho `../08-order-lifecycle/`).
- Backtest me **fees, spread aur slippage** zaroor jodte hain, nahi to backtest jhootha profit dikhata hai.

## Yaad rakhne wali baatein
- Spread aur slippage chhupe kharche hain.
- Market = tez, par rate pe control nahi. Limit = rate pe control, par fill ki guarantee nahi.
- Candle = Open, High, Low, Close.
