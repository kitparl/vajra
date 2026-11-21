---
title: Risk engine aur kill-switch
topic: risk-management
level: intermediate
---

# Risk engine aur kill-switch

## 1. Risk engine kya hai?
`risk-service` (Spring Boot, Java) ek alag chalne wala program hai. **Har order** exchange ko jaane se pehle isse guzarta hai.

**Example (building ka guard):** Building me jaane se pehle guard aapse puchta hai "kaun ho, kahan jaana hai, register me naam likho". Guard ka kaam hai rokna. Agar guard ko kahin "ghoose" khilao to wo guard nahi raha. Risk engine ke saath bhi aisa hi hai.

## 2. Kya check karta hai?
- Position size zyada to nahi?
- Total exposure limit ke andar hai?
- Leverage max se zyada to nahi?
- Aaj ka loss limit cross to nahi hua?
- Ek second me bahut zyada orders to nahi aa rahe? (order rate)
- Is strategy ya is exchange ka cap to full nahi?

Jawab 3 me se ek: **approve**, **reject**, ya **resize** ("itna bada nahi, itna chhota karke bhejo").

## 3. "Deterministic" ka matlab
Same input, **hamesha same output**. Isme koi ML, koi LLM, koi randomness nahi. Isliye hum predict kar sakte hain ki wo kya karega, aur test likh sakte hain.

Kyun? Agar risk engine bhi "seekhta" hota aur model galat seekh jata, to sabse bada safety net hi toot jata.

## 4. Fail-closed
**Matlab:** shak ho to **mana kar do**.

**Example:** ATM ka server down hai to ATM paisa nahi nikalta (ya galti se sabko paisa dene lagta?). Sahi design: **nahi nikalta**.
Hamare system me: agar `core-service` risk-service ko call kare aur jawab na aaye (timeout/error), to order **reject** hota hai. Order kabhi "chalo shayad theek hoga" nahi bhejte.

## 5. Kill-switch
**Matlab:** emergency button jo saari trading ek saath rok deta hai.

Kill-switch dabne par:
1. Saare open orders cancel.
2. Saari positions flatten (band).
3. Trading "halted" mark hoti hai.
4. Wapas chalu karne ke liye **insaan ko khud manually** resume karna padta hai. Apne aap kabhi chalu nahi hota.

**Kab dabta hai?** Manual (aap UI se dabate ho) ya automatic (jaise daily loss limit cross, ya exchange data me gadbad).

## 6. Pura flow ek picture me
```
signal-service  --signal-->  core-service (order intent banata hai)
                                  |
                                  v   (sync call: "kya ye order theek hai?")
                             risk-service
                                  |
                  approve / reject / resize
                                  |
                                  v
                       core-service exchange ko bhejta hai
```

## 7. Test kya karne chahiye?
- Limit se bada order -> reject.
- Risk service band -> order reject (fail-closed).
- Kill-switch -> sab open orders cancel aur trading halted.
- Kill-switch ke baad bina manual resume ke koi order nahi jaana chahiye.

## Yaad rakhne wali baatein
- Risk engine = alag process, deterministic, ML nahi.
- Fail-closed: shak ho to reject.
- Kill-switch = emergency stop, manual resume.
- Learned models risk limits **kabhi** nahi badal sakte.
