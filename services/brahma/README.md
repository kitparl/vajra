# core-service (folder: brahma)

**Stack:** Spring Boot (Java 25 LTS), Flyway
**Owns DB schema:** `core` (own role, own migrations — never touch other schemas)

**Does:** exchange connectors (Delta first), market data, instruments, OMS (state machine, idempotency, reconciliation), positions, paper broker, audit log.

**Does not:** decide trades, change risk limits, run ML.

Details: `docs/knowledge/0004-service-responsibilities.md`.

## Run and test
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home   # laptop, see 0018
cd services && mvn -B verify -pl brahma -am     # unit + Testcontainers integration tests (needs Docker)
scripts/compose.sh up -d --build core-service          # from repo root
curl localhost:8081/actuator/health
```
Config (env): `DB_URL` (jdbc), `DB_USER`, `DB_PASSWORD` (startup fails naming any missing one), `PORT`. Migrations: `src/main/resources/db/migration` (Flyway, schema `core`).
