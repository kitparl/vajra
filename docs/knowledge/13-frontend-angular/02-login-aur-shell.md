---
title: Login, CSRF aur shell
topic: frontend-angular
level: intermediate
---

# Login, CSRF aur shell

## Chhota example
Ek building me sirf aap rehte ho:
- Gate par **guard** hai, yaani Tailscale. Wo sirf aapke phone aur laptop ko andar aane deta hai.
- Andar flat ka **taala** hai, yaani login.
- Darwaza khulne par aapko ek **pass** milta hai, yaani session cookie.
- Har bade kaam (order, kill-switch) ke liye ek **parchi** bhi dikhani hoti hai, yaani CSRF token. Ye parchi sirf aapka apna ghar (same website) padh sakta hai.

## Hamare project me
- **Login:** pehle `GET /api/v1/auth/csrf` aata hai, jo parchi (cookie) deta hai. Phir `POST /api/v1/auth/login` hota hai. Angular parchi ko khud header me laga deta hai (`withXsrfConfiguration`).
- **Login ke baad parchi badal jaati hai,** aur Angular har request par cookie dobara padhta hai, isliye nayi parchi apne aap lag jaati hai.
- **5 baar galat password** daalne par 15 minute ka lock lagta hai. Screen par "kitne minute baad try karo" dikhta hai.
- **Password kahin save nahi hota.** Galti hone par field khaali hoti hai aur cursor wapas password par aa jata hai.
- **Top bar:**
  - Mode badge: PAPER saada, TESTNET amber, aur koi anjaan mode laal rang me apne asli naam se. Paise wali screen par kabhi galat mode nahi dikhna chahiye.
  - Connection dot: har 15 second me check hota hai. 5 second me jawab na aaye to "offline" dikhata hai. Session khatam ho to login par bhej deta hai.
  - Kill-switch button: abhi band hai, slice 4 me chalu hoga.
- **Phone par** screen tabs me badal jaati hai (1024 px se chhoti screen). Laptop par grid dikhta hai.

## CSP ka sabak
Hamari CSP kehti hai: "sirf apni files, koi inline script nahi". Angular ka ek optimization (`inlineCritical`) chupke se ek inline script daal deta tha. CSP use rok deti thi, aur poori stylesheet load hi nahi hoti thi. Isliye wo optimization band kiya, aur CI check karta hai ki `index.html` me koi inline script na ho.

Formal record: `../0030-terminal-access-login-and-signal-age.md`.
