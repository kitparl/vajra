# gateway-bff (dvara)

**Stack:** Spring Boot (Java 25 LTS), Flyway
**Owns DB schema:** `gateway` (own role, own migrations — never touch other schemas)

**Does:** dashboard REST API, WebSocket gateway for the UI, users/preferences, watchlists, alerts, notifier, scheduler.

**Does not:** contain order, fee or position logic. User actions are forwarded to core/risk APIs.

Details: `docs/knowledge/0004-service-responsibilities.md`.

## Run and test
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home   # laptop, see 0018
cd services && mvn -B verify -pl dvara -am     # unit + Testcontainers integration tests (needs Docker)
scripts/compose.sh up -d --build dvara          # from repo root
curl localhost:8080/actuator/health
```
Config (env): `DB_URL` (jdbc), `DB_USER`, `DB_PASSWORD` (startup fails naming any missing one), `PORT`. Migrations: `src/main/resources/db/migration` (Flyway, schema `gateway`).
