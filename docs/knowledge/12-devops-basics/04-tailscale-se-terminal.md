---
title: Tailscale se terminal kahin se bhi
topic: devops-basics
level: beginner
---

# Tailscale se terminal kahin se bhi

## Chhota example
Socho aapke ghar, office aur phone ke beech ek **private surang** ho, jisme sirf aapke device ja sakte hain. Bahar ki sadak (internet) se koi andar nahi aa sakta. **Tailscale** yahi surang hai.

## Kyun
- Terminal se order lagta hai aur kill-switch chalta hai. Ise poore internet ke saamne kholna bada risk hai, kyunki bots din-raat login try karte rehte hain.
- Tailscale ke saath VPS par **koi port bahar nahi khulta**. Phone 4G par ho ya kisi Wi-Fi par, surang se terminal tak pahunch jata hai.
- Tailscale HTTPS certificate khud deta hai, isliye domain name nahi chahiye.

## Kaise jude hain
```
phone/laptop --(Tailscale)--> VPS 127.0.0.1:8088 --> ui (Nginx) --> gateway-bff
```

## Setup (ek baar)
1. VPS par: `curl -fsSL https://tailscale.com/install.sh | sh`, phir `sudo tailscale up` chalao aur browser me login karo.
2. `sudo tailscale serve --bg --https=443 http://127.0.0.1:8088`
3. Phone aur laptop par Tailscale app install karo aur usi account se login karo.
4. `https://<vps-name>.<tailnet>.ts.net` kholo.
5. Check karo: `sudo ss -tlnp` me public address par sirf `sshd` dikhna chahiye.

## Yaad rakho
- VPS par `scripts/compose-vps.sh` chalao, **`compose.sh` nahi**. `compose.sh` laptop wala dev overlay lagata hai, jo cookie ko non-secure kar deta hai aur gateway ka port khol deta hai.
- Pehli baar password hash `.env` me single quotes me daalna hai, kyunki hash me `$` hota hai.

Formal record: `../0030-terminal-access-login-and-signal-age.md`.
