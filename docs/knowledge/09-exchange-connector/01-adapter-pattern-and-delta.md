---
title: Adapter pattern aur Delta Exchange
topic: exchange-connector
level: intermediate
---

# Adapter pattern aur Delta Exchange

## 1. Problem
Har exchange ka API alag hai: symbol ke naam alag, fees alag, errors alag. Agar core me har exchange ka alag code ghusa diya to code jaldi ulajh jayega.

## 2. Adapter pattern
**Example (travel plug adapter):** India ka plug America me nahi lagta. Aap ek **adapter** lagate ho. Laptop wahi rehta hai, sirf adapter badalta hai.

Hamare core ko ek **common interface** pata hai. Har exchange us interface ko implement karta hai.

```java
// core ko yahi pata hai
interface ExchangeConnector {
    // market data
    void subscribeTicker(Instrument i);
    List<Candle> backfill(Instrument i, Instant from, Instant to);
    // trading
    OrderAck placeOrder(OrderRequest r);
    void cancelOrder(String clientOrderId);
    // account
    List<Position> positions();
    List<Balance> balances();
    // metadata
    List<Instrument> instruments();
}

class DeltaConnector implements ExchangeConnector { /* Delta ki baatein yahan */ }
class SimulatedConnector implements ExchangeConnector { /* paper trading */ }
```
Naya exchange = naya class. Core code wahi.

## 3. Common model
Sab exchange se aaya data ek jaisa banta hai: `Instrument`, `Order`, `Fill`, `Position`, `Balance`. Exchange ki alag baatein (symbol mapping, fee, margin rule) **sirf adapter ke andar** rehti hain.

## 4. Naya exchange jodne ke steps
1. Instruments aur symbols common model se map karo.
2. Interface implement karo, errors map karo.
3. Fee aur rate-limit profile likho.
4. Shared test suite chalao (simulated exchange + testnet).
5. Kuch din **paper trading**, tab hi live.

## 5. Simulated adapter pehle
Asli exchange se pehle ek **nakli exchange** banate hain. Isse test aur paper trading bina paise ke ho jaati hai. Dusra asli exchange jaldi isliye jodte hain ki pata chale hamara design sirf Delta ke liye to nahi bana.

## 6. Delta Exchange (India) ki khaas baatein
*(Ye 2026-11-21 ka snapshot hai, implement karne se pehle docs dobara check karo. Formal record: `../0007-exchange-connector-design.md`.)*
- **India aur Global alag hain.** India keys Global API par nahi chalti.
- **Testnet alag hai.** Production keys testnet par nahi chalti, aur demo keys production par nahi.
- **WebSocket** public aur private alag.
- Product id chahiye, isliye pehle product list lao.
- Server AWS Tokyo me, isliye hamara server bhi paas rakhna achha.

## 7. API keys ki safety
- **Least privilege:** sirf **Read** chahiye to Trading permission mat do.
- **IP whitelist** lagao.
- Keys **kabhi git me nahi**. Secret manager ya env var me.
- Testnet aur production ki keys **alag**.

## 8. Rate limit
Exchange kehta hai "1 second me itni hi requests". Zyada bheji to **block**. Isliye connector me rate-limit policy hoti hai: requests ginta hai, jyada ho to rukta hai (**backoff**).

**Example:** Bank counter par token system. Ek ek karke bulaya jaata hai, dhakka-mukki nahi.

## 9. Disconnect
Market data ka WebSocket toot sakta hai. Connector **dobara connect** karta hai aur beech ke chhute hue candles **backfill** karta hai, warna chart me hole reh jayega.

## Yaad rakhne wali baatein
- Adapter = common plug, har exchange ek adapter.
- Exchange ki baatein adapter ke andar hi.
- Pehle simulated, phir testnet, phir paper, phir live.
- API keys: least privilege, IP whitelist, secret manager.
