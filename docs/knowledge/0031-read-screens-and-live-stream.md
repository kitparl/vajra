---
number: 0031
title: Read screens and the live stream (Phase 5 slice 2)
type: decision
created: 2026-11-28
status: current
---

# Read screens and the live stream

The terminal now shows the market, orders, fills, positions, P&L and the halted state live, on phone and laptop. This slice is read-only: no order placement and no kill-switch actions.

## Upstream reads
- **core-service:**
  - `GET /api/v1/quotes` returns `{symbol, bestBid, bestAsk, markPrice, venueTime, receivedAt, stale}` per trading-enabled symbol. Decimals are strings.
  - `stale` uses `QuoteCache.MAX_AGE` (10 s), which is now the single shared constant. Position and paper code read it too.
  - `GET /api/v1/status` returns `{tradingMode}`.
- **risk-service:** both endpoints sit behind `RISK_API_TOKEN` and are read-only.
  - `GET /api/v1/limits` returns `risk_limit` rows.
  - `GET /api/v1/rejections?limit=1..200` (default 50; anything else gives 400) returns the newest `risk_decision` rows where `approved = false`.

## gateway-bff REST (`com.vajra.gatewaybff.upstream`, `api.ReadController`)
- **Endpoints:** GET only, and every one needs a session.
  - `/api/v1/instruments` (only `tradingEnabled`)
  - `/api/v1/quotes`
  - `/api/v1/positions`
  - `/api/v1/pnl`
  - `/api/v1/orders`
  - `/api/v1/orders/{id}`
  - `/api/v1/candles`
  - `/api/v1/risk/status`
  - `/api/v1/risk/limits`
  - `/api/v1/risk/rejections`
- **Bodies pass through as JSON.** The gateway models nothing beyond the instruments filter. Path and query values go through URI templates, so they are encoded.
- **Errors:**
  - These return **503** `{"error":"UPSTREAM_UNAVAILABLE","upstream":"core"|"risk"}`:
    - a connection failure or timeout
    - an upstream 5xx
    - an **upstream 401 or 403**
    - an empty body
  - The 401/403 case matters: a wrong `RISK_API_TOKEN` must not look like "not logged in" to the UI.
  - Every other 4xx passes through with its status and body.
- **Timeouts:** connect 1 s, read 3 s. One call takes at most about 4 s, which is under the UI's 5 s session-poll timeout.
- **Fail fast:** a blank `RISK_API_TOKEN` or upstream URL stops startup. A missing token used to fail silently, with every risk call returning 401.
- **Trading mode:** the `/session` mode now comes from core `/status` (`CoreClient.tradingMode()`). If core is unreachable it is `"UNKNOWN"`, and the badge shows it in red. The gateway's own `TRADING_MODE` is gone.

## gateway-bff WebSocket `/ws` (`com.vajra.gatewaybff.live`)
- **Handshake:** needs a valid session (otherwise 401) **and** an `Origin` exactly equal to `GATEWAY_UI_ORIGIN` (otherwise 403; a missing Origin also gives 403).
  - No CSRF exception is needed: the handshake is a GET, and CSRF never applies to GET.
  - `GATEWAY_UI_ORIGIN` must be a bare origin: no trailing `/` and no path. Startup fails otherwise.
- **Messages:** `{type, data}` with `type` one of `hello | quote | order | fill | position | risk | upstream`.
  - The first message is always `hello` `{tradingMode}`.
  - `risk` is sent once on connect, then when it changes, and again every 10 s even if unchanged (so a reordered message cannot stick).
  - `upstream` `{upstream: core|risk, available}`: while a poll fails with an unreachable upstream, every failed poll broadcasts (about every 1 s for a refused connection, up to about 5 s while core times out, so always inside the UI's 10 s watchdog) `available:false`; the first success afterwards broadcasts `available:true` once.
- **Kafka:** consumer group `gateway-bff`, `auto-offset-reset=latest`, topics `order.updated`, `fill.created` and `position.updated`.
  - Each `EventEnvelope` is decoded with contracts-java, and decimals stay strings.
  - A malformed record is logged and skipped. It never stops the consumer.
  - `missing-topics-fatal=false`, so the gateway starts and REST works with Kafka down.
- **Pollers:** quotes every 1 s and risk status every 2 s, only while at least one socket is open. A failure is logged at WARN at most once a minute per poller, and polling continues.
- **Slow clients:**
  - Each socket is wrapped in `ConcurrentWebSocketSessionDecorator(5000 ms, 256 KiB)`.
  - Every send runs on its own virtual thread, so a stalled phone never blocks the poller or Kafka thread. Tomcat's native send can block for about 20 s.
  - The stalled socket hits the limit and only that socket is closed.
- **Session end:**
  - At the handshake the socket is bound to a stable session attribute `vajra.liveKey`, not the session id, because login rotates the id. When the HTTP session is destroyed (logout, idle timeout, or the 8 h limit, which the filter enforces by invalidating the session), its sockets close with **4401**.
  - A new socket is registered as *pending* before the hello fetch. Broadcasts skip it, but a logout still closes it.
  - After it is marked ready, it gets a catch-up risk state if risk changed while it was pending.

## UI (`ui/src/app/data`, `watchlist`, `orders`, `positions`, top bar)
- **LiveService:**
  - Connects to `wss?://<same host>/ws`. Backoff is 1, 2, 5 and 10 s (cap 10).
  - On **every** open, including the first, it runs the `onReconnect` hooks so the screens re-take their snapshot after the socket is live. This closes the gap between the REST snapshot and the socket. Each hook is unregistered on destroy.
  - Close code 4401 sends the user to /login and clears the mode, risk and status.
- **Merging:** the gateway sends each event on its own thread, so two events for one order can arrive out of order.
  - Orders keep the newer `updatedAt`.
  - A snapshot merges into the current rows instead of replacing them.
  - REST positions have no `updatedAt`, so a live position row that arrived after the load started wins.
  - A superseded load is dropped.
- **Fresh P&L:** core computes mark and unrealized P&L from its live quote cache. The UI re-fetches `/pnl` (top bar) and `/positions` on `quote` and `position` messages, at most once every 2 s (`createThrottle`).
- **No money math in the UI.** Strings are shown verbatim. The 24 h change % (against the close of the candle 24 h ago, refreshed every 10 min) is the only number the UI computes, and it is for display only.
- **Watchlist:** a 600 ms CSS flash on change, ▲/▼ plus colour, and a "stale" text marker. HALTED is shown as text, never as colour alone.
- **Upstream down on the socket:** `LiveService` exposes `coreAvailable` / `riskAvailable`. While core is down every watchlist row shows "stale", the watchlist, positions and top-bar P&L show "core unavailable", and the mode badge shows UNKNOWN. It clears on `available:true` or the next quote. A watchdog force-closes a socket that is silent for 10 s (half-open phone sockets) and reconnects with the normal backoff.
- **Mode badge:** the polled session value wins; hello is used only until the first session value exists.
- **Upstream 503:** an inline "core unavailable" or "risk unavailable" notice, shown only in the panel whose call failed (including the top-bar P&L slot).

## Compose
- gateway-bff gets:
  - `CORE_BASE_URL`
  - `RISK_BASE_URL`
  - `RISK_API_TOKEN`
  - `KAFKA_BOOTSTRAP`
  - `GATEWAY_UI_ORIGIN: ${GATEWAY_UI_ORIGIN:-http://localhost:8088}`
- **On the VPS**, set `GATEWAY_UI_ORIGIN=https://<vps-name>.<tailnet>.ts.net` in `.env`, otherwise every socket is refused with 403.
- No new published ports.

## Demo (laptop, through Nginx :8088, 2026-11-28)
- **Read endpoints:** all ten answered with real data. `/session` gave `PAPER`, and quotes were fresh (`stale:false`).
- **Live order:** a WebSocket client got `hello`, then `risk`, then a quote every second. A manual PAPER SELL of 1 BTCUSD (curl to core) arrived live:
  - order NEW → APPROVED → SENT → ACKED → FILLED
  - then `fill` (59834.5, fee "0.02991725")
  - then `position` size 0
  - `/pnl` changed with it
- **Cold risk-service:** the first manual order, about a minute after risk-service was rebuilt, was `RISK_REJECTED RISK_CHECK_FAILED` because core's approval call timed out while risk-service was still cold. This is the existing fail-closed path, not a slice-2 change. The retry filled.
- **Handshake:** wrong Origin gave 403, no session gave 401, and the right Origin with a session connected.
- **Logout:** the socket closed with **4401**.
- **Core stopped:**
  - `/positions` gave 503 `UPSTREAM_UNAVAILABLE core`.
  - `/session` gave `tradingMode: "UNKNOWN"`.
  - Risk reads kept working.
  - After `docker start`, positions were back in about 8 s.
- **Gateway restart:** the socket dropped (1006) and the in-memory session was gone (401). After logging in again everything worked. In the browser, this is the UI going to "reconnecting" and then to /login.
- **Memory check:** OK. gateway-bff 174 of 384 MiB, ui 4 of 32 MiB, 1213 MiB in total.
- **Token hygiene:** the risk token does not appear in the gateway logs.

## Known limits and follow-ups
- **Kafka replay:** the fixed consumer group commits offsets, so `latest` applies only on the very first start. After a gateway outage, the backlog is replayed to connected sockets. That is harmless for display.
- **Risk message order:** per-send virtual threads can reorder two `risk` messages that are milliseconds apart. The 10 s periodic resend corrects a wrong HALTED within about 12 s.
- **Pending-window order event:** an `order.updated` committed after the client's snapshot but before the socket is marked ready (normally tens of ms, up to about 4 s if core hangs) can be missed for orders until the next event or reconnect. Positions heal through the quote refetch.
- **Core 4xx in a poll:** an `UpstreamRejectedException` from core (not "unavailable") broadcasts nothing, so the 10 s watchdog keeps reconnecting while the badge says core is up.
- **VPS origin:** if `GATEWAY_UI_ORIGIN` is missing on the VPS, the localhost default makes every socket get 403, and the UI loops on "reconnecting" with no reason shown. Set it in `.env` before the first VPS deploy.
- **Idle timeout:** the 2 s `/pnl` and `/positions` refetches keep the HTTP session busy, so an open tab stays logged in until the 8 h absolute limit.
- **Handshake/register window:** a logout that lands between the handshake interceptor and `register` (a few microseconds) can leave a socket open.
- **Rejections index:** the rejections query has no index (`not approved`). Add a V3 partial index `(decided_at desc) where not approved` before live trading.
- **Noisy WARN:** a wrong risk token logs a WARN on every poll.
- **Minor:**
  - `afterConnectionEstablished` blocks a container thread on the core and risk calls.
  - A NG0956 dev-mode warning comes from the watchlist flash (`track v`). A Web Animations directive would be cleaner.
  - The 24 h reference assumes 1-minute candles.
  - A background tab's reconnect timers are throttled by the browser.
