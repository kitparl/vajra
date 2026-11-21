---
title: Instruments, quotes aur candles
topic: exchange-connector
level: beginner
---

# Instruments, quotes aur candles

## 1. Instrument kya hai
**Example:** Sabzi mandi me har cheez ka ek "label" hota hai: naam (tamatar), ek unit (1 kg), aur kitne paise tak bhaav badal sakta hai (50 paise).

Exchange pe har cheez jise hum khareed-bech sakte hain, use **instrument** kehte hain. Uska bhi label hota hai:
- **Symbol:** `BTCUSD` (naam)
- **Contract value:** 1 contract = `0.001 BTC`. Matlab 5 contract kharide to 0.005 BTC ka sauda hua.
- **Tick size:** price kam se kam `0.1` dollar se badal sakta hai. 57000.15 jaisa price allowed nahi.
- **Fees:** maker 0.02%, taker 0.05%.

Hamara **instrument master** (`core.instrument` table) Delta se ye saare label laakar rakhta hai. Trading sirf BTCUSD aur ETHUSD pe "on" hai.

## 2. Quote kya hai
**Example:** Dukaan pe likha hai "Hum 100 me khareedenge, 101 me bechenge".
- **Best bid** = sabse zyada daam jo koi khareedne wala de raha hai (100)
- **Best ask** = sabse kam daam jis pe koi bech raha hai (101)

Hum har 2 second Delta se ye quote laate hain aur memory me rakhte hain. Quote 10 second se purana ho gaya to use "basi" (stale) maante hain, uspe trade ka faisla nahi hoga.

## 3. Candle kya hai
Ek minute me price kahan se shuru hua, kitna upar gaya, kitna neeche aaya aur kahan band hua: **Open, High, Low, Close** (OHLC) + kitna volume hua. Ye ek **1-minute candle** hai.

Hum har minute nayi candles `core.candle_1m` me save karte hain.

## 4. Gap backfill
**Example:** Aap 3 din chhutti pe the, diary nahi likhi. Wapas aake pichhle 3 din ki entries ek saath bhar di.

`brahma` band tha to un minutes ki candles nahi aayi. Wapas chalu hote hi wo **aakhri save hui candle se** aage ki saari candles Delta se maang leta hai. Isliye koi minute chhoota nahi rehta. Pehli baar chalne pe wo 7 din ka purana data bhar deta hai.

## 5. Project me kahan hai
- `services/brahma/.../connector/delta/`: Delta se baat karne wala code (sirf **testnet**)
- `instrument/`: instrument master
- `marketdata/`: quotes, candles, backfill
- API: `GET /api/v1/instruments`, `GET /api/v1/candles?symbol=BTCUSD&from=...&to=...`

Formal record: `../0022-market-data-by-rest-polling.md`.
