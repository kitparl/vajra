---
title: Terminal UI aur Angular basics
topic: frontend-angular
level: beginner
---

# Terminal UI aur Angular basics

**Formal record:** `../0008-ui-design-direction.md`.

## 1. UI me kya hoga
Watchlist, chart, order ticket, positions, orders, strategies/models, backtests, risk aur kill-switch, exchange health, alerts, audit viewer.

## 2. Angular kya hai?
Web UI banane ka framework (TypeScript me). Version **22**. Screen ko chhote **components** me todta hai.

**Example (LEGO):** Poori screen ek LEGO set hai. Chart = ek block, watchlist = ek block, order ticket = ek block. Ek block badlo, baaki waise ke waise.

```ts
@Component({
  selector: 'app-price',
  template: `<span>{{ price() }}</span>`,
})
export class PriceComponent {
  price = signal(50000);   // signal: value badli to screen khud update
}
```

## 3. TypeScript sirf yahan
TypeScript/Node ka use **sirf UI** me hai. Backend sirf Java aur Python me (dekho `../0011-backend-java-and-python-only.md`).

## 4. UI sirf gateway-bff se baat karta hai
UI seedha `core-service` ya `risk-service` se baat **nahi** karta. Sirf `gateway-bff` se (REST + WebSocket).

**Example:** Hotel guest kitchen me nahi jaata, receptionist se baat karta hai.

Fayda: ek hi jagah login/security, UI ko backend ke andar ka dhancha nahi pata.

## 5. Live feel
- WebSocket se rate live aate hain.
- Rate badalne par chhoti flash (hara/laal), par layout kood-phand na kare.
- Kill-switch button hamesha dikhta aur confirm ke saath.

## 6. UI me kya NAHI
UI order, fee ya position ka **hisaab nahi** karta. Wo backend ka diya dikhata hai. Warna UI aur backend ke numbers alag ho jayenge.

## Yaad rakhne wali baatein
- Angular = components ka LEGO.
- UI sirf gateway se baat karta hai.
- UI sirf dikhata hai, hisaab backend me.
