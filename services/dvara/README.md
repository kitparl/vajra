# gateway-bff (dvara)

**Stack:** Spring Boot (Java 25 LTS), Flyway
**Owns DB schema:** `gateway` (own role, own migrations — never touch other schemas)

**Does:** dashboard REST API, WebSocket gateway for the UI, users/preferences, watchlists, alerts, notifier, scheduler.

**Does not:** contain order, fee or position logic. User actions are forwarded to core/risk APIs.

Details: `docs/knowledge/0004-service-responsibilities.md`.
