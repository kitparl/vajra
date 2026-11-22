# Knowledge base

High-level design, service responsibilities, important flows, strategies, concepts and learnings for the project. Committed with the code.

## Rules
- Files are named `NNNN-slug.md` with the **next free number**. Numbers are sequential, never reused, never renumbered, so reading in order = reading in the order things were learned.
- Every new design decision, flow, strategy, concept or lesson becomes a **new file**. Old files are only corrected or marked `Superseded by: NNNN`.
- Add the new file to the index below in the same change.
- Frontmatter: `number`, `title`, `type` (design | service | flow | strategy | concept | learning | decision), `created`, `status` (current | superseded).

## Index
| # | Title | Type |
|---|---|---|
| 0001 | Project overview | design |
| 0002 | Tech stack and versions | decision |
| 0003 | Architecture and data ownership | design |
| 0004 | Service responsibilities | service |
| 0005 | Trade lifecycle flow | flow |
| 0006 | Self-learning loop | design |
| 0007 | Exchange connector design (Delta first) | design |
| 0008 | UI design direction | design |
| 0009 | Backtesting and learning pitfalls | learning |
| 0010 | Build phases and roadmap | design |
| 0011 | Backend uses only Java and Python (no NestJS) | decision |
| 0012 | Repository layout (monorepo) | decision |
| 0013 | Service folder names (Sanskrit) | decision |
| 0014 | Deployment split - live path on VPS, research on laptop | decision |
| 0015 | Topic folders get a number prefix | decision |
| 0016 | Database roles, schemas and migrations (Phase 1) | design |
| 0017 | Contracts pipeline (Protobuf, buf, code generation) | design |
| 0018 | Phase 1 build decisions, versions and memory measurements | decision |
| 0019 | Lessons from LifeOS (same VPS) | learning |
| 0020 | Every release doc gets its own version | decision |
| 0021 | Task type decides the version bump, plus commit message rule | decision |
| 0022 | Market data by REST polling from Delta testnet (Phase 2 slice 1) | decision |
| 0023 | Order management and paper trading in brahma (Phase 2 slice 2) | decision |
| 0024 | Risk gate and kill-switch (maryada + brahma, Phase 2 slice 3) | decision |
| 0025 | Role names in code, Sanskrit only in folder names | decision |
| 0026 | Heartbeat strategy and the Kafka signal path (Phase 2 slice 4) | decision |

## Hinglish topic notes (beginner friendly)
Numbered files above are the formal design record. The **topic folders** below explain the same ideas in simple Hinglish so that everyone can understand easily. Read them in this order if you are new.

Topic folders are named `NN-topic/` with the next free two-digit number (never renumbered or reused). A new topic goes at the end of this table.

| Folder | Kya seekhoge |
|---|---|
| `01-trading-basics/` | Spot, futures, leverage, liquidation, order types, candles |
| `02-risk-management/` | Stop loss, position size, drawdown, risk engine, kill-switch |
| `03-architecture-basics/` | Service, monolith vs microservices, API, data ownership |
| `04-java-spring-boot/` | Spring Boot kya hai, layers, scheduler, WebSocket |
| `05-python-fastapi/` | FastAPI, Pydantic, Python kyun ML ke liye |
| `06-databases/` | PostgreSQL, schema, migrations, Redis |
| `07-kafka-and-events/` | Event, topic, producer/consumer, Kafka kyun |
| `08-order-lifecycle/` | Order state machine, idempotency, reconciliation |
| `09-exchange-connector/` | Adapter pattern, Delta Exchange, API keys, rate limits, instruments, candles |
| `10-backtesting-and-learning/` | Backtest, lookahead, overfitting, shadow/paper, model registry |
| `11-ai-llm-usage/` | LLM kahan use karna hai aur kahan nahi |
| `12-devops-basics/` | Docker, compose, CI, secrets, testnet vs live |
| `13-frontend-angular/` | Terminal UI, Angular basics, UI sirf gateway se baat karta hai |
