---
title: Order state machine, idempotency, reconciliation
topic: order-lifecycle
level: advanced
---

# Order state machine, idempotency, reconciliation

## 1. State machine
Order ek hi jagah par nahi rehta, wo **stages** se guzarta hai. Har stage ko **state** kehte hain. Aur har state se kuch hi states par jaana allowed hai.

**Example (courier parcel):** Booked → Picked up → In transit → Delivered. "Delivered" se wapas "Booked" nahi ho sakta.

Hamare order ke states:
```
NEW -> SENT -> ACKED -> PARTIALLY_FILLED -> FILLED
             \-> REJECTED
 (kisi bhi jagah se) -> CANCELLED / EXPIRED
 (timeout) -> UNKNOWN  (reconcile hone tak)
```
| State | Matlab |
|---|---|
| NEW | Hamare system me bana, abhi bheja nahi |
| SENT | Exchange ko bhej diya |
| ACKED | Exchange ne maana "order mila" |
| PARTIALLY_FILLED | Thoda hissa kharida/becha gaya |
| FILLED | Poora ho gaya |
| REJECTED | Exchange ne mana kar diya |
| CANCELLED | Hum ne ya exchange ne radd kiya |
| UNKNOWN | Bheja par jawab nahi mila, pata nahi kya hua |

**Fayda:** "FILLED" order ko galti se phir "SENT" karna code se hi **impossible** ho jaata hai.

## 2. Idempotency (do baar karo, ek hi baar ho)
**Problem:** Order bheja, network ruk gaya, jawab nahi aaya. Aapne dobara bhej diya. Ab 2 orders ho gaye, double paisa laga!

**Solution:** Har order ke saath ek **unique client order id** bhejo. Exchange ya hamara system dekhta hai: "ye id pehle aa chuki hai" → naya order nahi banata.

**Example:** Train ticket ka PNR. Do baar booking dabaya par PNR same, to ek hi ticket.

```java
// pseudo code
if (orderRepo.existsByClientOrderId(clientOrderId)) {
    return orderRepo.findByClientOrderId(clientOrderId);  // purana order wapas
}
// warna naya order banao
```
DB me `client_order_id` par **UNIQUE** constraint bhi lagate hain, taaki code ki galti ho tab bhi DB rok de.

## 3. Timeout ka sahi tareeka
Order bheja, jawab nahi aaya? **Dobara mat bhejo.** State = `UNKNOWN`. Pehle exchange se poocho "mere paas ye client order id wala order hai?" Jawab ke baad hi aage badho.

**Example:** Pizza order kiya, app crash. Dobara order karne ke bajaye pehle "My orders" dekho. Warna 2 pizza aa jayenge.

## 4. Reconciliation (hisaab milana)
Hamara system aur exchange dono alag record rakhte hain. Kabhi kabhi mismatch ho jaata hai (network, reconnect, exchange ki galti).

**Reconciliation:** thodi thodi der me (aur har reconnect ke baad) hum:
1. Exchange se open orders aur positions maangte hain.
2. Hamare record se milate hain.
3. Farak mila to **theek karte hain ya alert dete hain**.

**Example:** Dukaan ke roz ke end me gallay ka cash register ke record se milana. Farak aaye to dhoondho kyun.

## 5. Kaun si failures handle karni hain
| Failure | Kya karte hain |
|---|---|
| Duplicate request | Same client order id → naya order nahi |
| Timeout | `UNKNOWN`, reconcile, phir aage |
| Partial fill | State machine sambhalti hai, risk ko batati hai |
| Reject | `REJECTED`, reason log |
| Exchange down / rate limit | Backoff, us exchange par naye order band, alert |
| Risk service nahi mila | **Reject** (fail-closed) |

## 6. Kaun se tests zaroor likhne hain
- Har allowed/not-allowed state transition.
- Same client order id do baar → ek hi order.
- Timeout → `UNKNOWN`, blind re-send nahi.
- Reconciliation me mismatch pakadna.

## Yaad rakhne wali baatein
- State machine = galat transition code se hi roko.
- Idempotency = unique client order id.
- Timeout par dobara mat bhejo, pehle reconcile.
- Reconciliation = exchange ke saath hisaab milana.
