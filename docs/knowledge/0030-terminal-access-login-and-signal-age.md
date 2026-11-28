---
number: 0030
title: Terminal access over Tailscale, gateway login and the consumer-side signal age (Phase 5 slices 0 and 1)
type: decision
created: 2026-11-28
status: current
---

# Terminal access, gateway login and signal age

## Slice 0: core rejects stale signals
- `vajra.signals.max-age` is `PT5M` by default: the producer limit of 4 minutes plus send time. Values that are null, zero or negative fail startup.
- A signal older than max-age never trades. It becomes a `RISK_REJECTED` order with reason `STALE_SIGNAL` and source `SIGNAL`. There is no risk call and no venue call.
  - The order is created through the same idempotent path as `place` (`OrderService.createOnce`), so a redelivery is still one row.
  - The state change writes the usual `order.updated` audit and outbox row.
- A signal exactly at max-age is still placed. A missing `generated_at`, or one more than 30 s in the future, is invalid and skipped.
- When a stale redelivery hits an order that already exists, it is logged as "already placed", or "stuck" for an order stuck in NEW. It is never logged as "rejected".

## Slice 1: how the terminal is reached
`phone/laptop → Tailscale (private, HTTPS) → VPS 127.0.0.1:8088 → ui (Nginx) → gateway-bff:8080`

- No port is open to the internet. Base `compose.yaml` publishes only `ui` on `127.0.0.1:8088`. `scripts/check-compose-ports.sh` enforces this in CI.
- **ui container:**
  - Nginx 1.29.8 runs as non-root on port 8080. The container is `read_only`, with `cap_drop: ALL`, `no-new-privileges`, 32 MB and one worker.
  - It proxies only `^~ /api/` (gzip off) and `= /ws`.
  - Security headers on every response:
    - CSP: `default-src 'self'`, with no inline script. The Angular build therefore uses `inlineCritical: false`, and CI checks that index.html has no inline script.
    - `frame-ancestors 'none'`
    - `no-referrer`
    - `nosniff`
    - `X-Frame-Options: DENY`
    - `Permissions-Policy`
  - `index.html` is `no-store`. Hashed assets are immutable. A missing `.js` or `.css` file returns 404.
- **gateway-bff login:**
  - Spring Security, with a JSON login (`POST /api/v1/auth/login`).
  - One user. `GATEWAY_ADMIN_USERNAME` and `GATEWAY_ADMIN_PASSWORD_HASH` are bcrypt cost 12, from env. The gateway **refuses to start without the hash**.
  - Session cookie `VAJRA_SESSION`: HttpOnly, SameSite=Strict, and `Secure` set by `GATEWAY_COOKIE_SECURE` (default true; the laptop dev overlay sets false). Idle timeout 30 min, absolute lifetime 8 h. The session id changes on login, and no session exists before login (`NullRequestCache`).
  - CSRF uses `csrf.spa()` with a cookie repository (`XSRF-TOKEN` → `X-XSRF-TOKEN`). It is required on every POST, login included. The token is rotated on login.
  - Lockout is **global**: 5 failures within 15 min lock login for 15 min. Attempts are serialized, so parallel requests cannot bypass it. The response is 429 with `Retry-After`.
  - Audit: `gateway.audit_event` records `LOGIN_OK` (written *before* the session is created), `LOGIN_FAILED` (username sanitized, never the password), `LOGIN_LOCKED` and `LOGOUT`. Logout always ends the session.
  - Public paths: `/actuator/health`, `/api/v1/auth/csrf` and `/api/v1/auth/login`. Everything else returns 401 JSON.
- **Angular shell:**
  - Angular 22.2.x with strict TypeScript and strict templates. OnPush and signals.
  - Login page: CSRF fetched before every attempt; values read from the inputs so autofill works; password cleared and focused after a failure; lock message in minutes.
  - Top bar:
    - Mode badge: `PAPER`, `TESTNET` (amber), or any other value shown raw in red.
    - Connection dot: a session poll every 15 s with a 5 s timeout and no overlapping requests. A 401 sends you to /login.
    - P&L placeholder.
    - Kill-switch: present but `aria-disabled` until slice 4.
    - Theme toggle and logout.
  - Grid layout on desktop, toggle tabs on screens under 1024 px.

## Runbook: first-time setup
1. **Password hash.** Run `scripts/gateway-password-hash.sh` and type the password; it is not echoed. Put the result in the git-ignored `infra/compose/.env` **in single quotes**, because a bcrypt hash contains `$`:
   ```
   GATEWAY_ADMIN_USERNAME=pranshu
   GATEWAY_ADMIN_PASSWORD_HASH='$2a$12$...'
   ```
   Existing installs need this before gateway-bff is recreated. Without it, gateway-bff crash-loops and `ui` never starts.
2. **Laptop:** `scripts/compose.sh up -d --build --wait gateway-bff ui`, then open `http://localhost:8088`.
3. **VPS**, using base compose only and never `compose.sh`, which adds the dev overlay:
   ```
   scripts/compose-vps.sh up -d --build --wait
   curl -s http://127.0.0.1:8088   # serves the login page (Nginx is plain http; Tailscale adds HTTPS)
   ```
4. **Tailscale on the VPS host**, done by Pranshu because it needs his account:
   ```
   curl -fsSL https://tailscale.com/install.sh | sh
   sudo tailscale up                       # log in once, in the browser
   sudo tailscale serve --bg --https=443 http://127.0.0.1:8088
   tailscale serve status                  # shows https://<vps-name>.<tailnet>.ts.net
   ```
5. **Phone and laptop:** install the Tailscale app, log in with the same account, then open `https://<vps-name>.<tailnet>.ts.net`.
6. **Check that nothing is public:** `sudo ss -tlnp` must show only `sshd` on a public address. 8088 must be on `127.0.0.1`, and Tailscale listens on its 100.x address.
7. **Switching TRADING_MODE:** recreate **gateway-bff together with core-service**, because the badge reads gateway-bff's own `TRADING_MODE`.

## Demo (laptop, 2026-11-28)
- **Through Nginx on :8088.**
  - All security headers were present.
  - `/api/v1/session` without a login gave 401. A login without the CSRF header gave 403. A wrong password gave 401 `BAD_CREDENTIALS`.
  - The right password gave 204, the XSRF token rotated, and `/session` returned `{"username":"pranshu","tradingMode":"PAPER"}`.
  - Logout with the rotated token gave 204, then `/session` gave 401.
- **Lockout:** 5 wrong passwords, then 429 `LOCKED` with `Retry-After: 900`. The audit table shows `LOGIN_FAILED`, `LOGIN_LOCKED`, `LOGIN_OK` and `LOGOUT`, and the failed-login detail has no password.
- **Stale signal:** a signal 10 minutes old went to Kafka. core logged `Stale signal ... rejected: age PT10M exceeds max PT5M` and stored a `SIGNAL RISK_REJECTED STALE_SIGNAL` PAPER order. Positions were unchanged.
- **Memory check:** OK. ui uses 2 of 32 MB, gateway-bff 145 of 384, and the stack 1186 MB in total.
- **Lesson:** when appending to `.env`, first make sure the file ends with a newline. During the demo, a missing newline joined the new `GATEWAY_ADMIN_USERNAME` line onto the `DELTA_TESTNET_API_SECRET` line. It was caught at gateway startup and split without reading the secret.

## Known limits and follow-ups
- **Idle timeout:** an open tab polls every 15 s, so the session stays alive until the 8 h absolute limit. "Idle" in practice means no tab is open.
- **Lockout:** it is global, so any device on the tailnet could lock the user out for 15 min. Only Pranshu's devices are on the tailnet.
- **Mode badge:** it shows the gateway's mode. Slice 2 should take the mode from core.
- **Minor:**
  - The login lock has no wait timeout.
  - `/ws` must become `^~ /ws/` if sub-paths appear.
  - Logout shows "offline" on a 401.
  - guestGuard can block /login for up to 5 s while the gateway is unreachable.
  - Client `X-Forwarded-For` is passed through. It matters only if the audit ever reads it.
