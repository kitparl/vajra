# vajra

Self-learning, multi-exchange autonomous trading platform. Crypto first (Delta Exchange India, testnet/paper by default).
Start with `docs/knowledge/0001-project-overview.md`.

## Repository layout
```
contracts/          Protobuf schemas (events + APIs), the only place shared shapes are defined
services/
  core-service/     Spring Boot  - connectors, market data, OMS, positions, audit   (schema: core)
  risk-service/     Spring Boot  - order approval, limits, kill-switch               (schema: risk)
  gateway-bff/      Spring Boot  - UI API, WebSocket, notifier, scheduler            (schema: gateway)
  research-service/ FastAPI      - features, backtests, training, model registry     (schema: research)
  signal-service/   FastAPI      - live strategies and inference, emits signals      (schema: signal)
ui/                 Angular terminal (frontend only)
infra/              docker-compose, Postgres bootstrap (schemas + roles)
scripts/            Dev helper scripts
.github/workflows/  CI
docs/knowledge/     Design records and Hinglish learning notes
docs/releases/      Release notes
```
