# vajra

Self-learning, multi-exchange autonomous trading platform. Crypto first (Delta Exchange India, testnet/paper by default).
Start with `docs/knowledge/0001-project-overview.md`.

## Repository layout
```
contracts/          Protobuf schemas (events + APIs), the only place shared shapes are defined
services/           folder name (service)
  brahma/           core-service      Spring Boot - connectors, market data, OMS, positions, audit (schema: core)
  maryada/          risk-service      Spring Boot - order approval, limits, kill-switch            (schema: risk)
  dvara/            gateway-bff       Spring Boot - UI API, WebSocket, notifier, scheduler         (schema: gateway)
  manthan/          research-service  FastAPI     - features, backtests, training, model registry  (schema: research)
  shankha/          signal-service    FastAPI     - live strategies and inference, emits signals   (schema: signal)
ui/                 Angular terminal (frontend only)
infra/              docker-compose, Postgres bootstrap (schemas + roles)
scripts/            Dev helper scripts
.github/workflows/  CI
docs/knowledge/     Design records and Hinglish learning notes
docs/releases/      Release notes
```
