---
title: Gateway bhi Kafka sunta hai
topic: kafka-and-events
level: intermediate
---

# Gateway bhi Kafka sunta hai

## Chhota example
Ek school ki announcement system me principal ki awaaz har class me jaati hai. Ek naya speaker reception par laga diya, taaki aane wale parents bhi sun sakein. Is speaker ka ek hi kaam hai, **sunna aur sunana**. Isse koi announcement *bheji* nahi ja sakti.

## Hamare project me
- **gateway-bff ab Kafka ka consumer hai.** Group ka naam `gateway-bff` hai, aur ye teen topics sunta hai:
  - `order.updated`
  - `fill.created`
  - `position.updated`
- **Message kaise aage jaata hai:** har message `EventEnvelope` me Protobuf hota hai. Gateway use contracts-java se kholta hai, JSON banata hai aur terminal ke WebSocket par bhej deta hai. Decimal values string hi rehti hain, kabhi `double` nahi bante.
- **Sirf padhta hai.** Gateway koi event publish nahi karta aur koi order nahi banata. Paisa aur order ka kaam sirf core aur risk ka hai.
- **`auto-offset-reset=latest`:** pehli baar start hone par ye sirf naye events sunta hai, purana itihaas nahi.
  - Group apni jagah (offset) yaad rakhta hai. Isliye gateway kuch der band rahe to wapas aane par chhoote hue events ek saath aa jaate hain.
  - Ye sirf screen ke liye hai, isliye koi nuksaan nahi.
- **Kharab message aaye to:** use log karke chhod diya jaata hai. Consumer rukta nahi.
- **Kafka band ho tab bhi** gateway start hota hai aur REST chalta rehta hai (`missing-topics-fatal=false`). Kafka wapas aate hi live updates phir shuru ho jaate hain.

Formal record: `../0031-read-screens-and-live-stream.md`.
