# vajra

Self-learning, multi-exchange autonomous trading platform. Crypto first (Delta Exchange India, testnet/paper by default).
Start with `docs/knowledge/0001-project-overview.md`.

## Repository layout
```
contracts/          Protobuf schemas + Java/Python code generation, the only place shared shapes are defined
services/           folder name (service)
  brahma/           core-service      Spring Boot - connectors, market data, OMS, positions, audit (schema: core)
  maryada/          risk-service      Spring Boot - order approval, limits, kill-switch            (schema: risk)
  dvara/            gateway-bff       Spring Boot - UI API, WebSocket, notifier, scheduler         (schema: gateway)
  manthan/          research-service  FastAPI     - features, backtests, training, model registry  (schema: research)
  shankha/          signal-service    FastAPI     - live strategies and inference, emits signals   (schema: signal)
  common/ common-py/  startup helpers shared by the Java / Python services (no business logic)
  pom.xml           Maven parent for all Java modules (all Java versions live here)
  pyproject.toml    uv workspace for all Python packages (one uv.lock)
  *.Dockerfile      one image recipe per language
ui/                 Angular terminal (frontend only)
infra/              docker-compose, Postgres bootstrap (schemas + roles)
scripts/            compose.sh (dev compose wrapper), check-memory.sh
.github/workflows/  CI
docs/knowledge/     Design records and Hinglish learning notes
docs/releases/      Release notes
```

## Quick start (laptop)
Needs Docker, Java 25, Maven, uv, buf. Details: `docs/knowledge/0018`.
```
cp infra/compose/.env.example infra/compose/.env
scripts/compose.sh up -d --build && scripts/check-memory.sh
cd services && mvn -B verify && uv run pytest
```
