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

## Hinglish topic notes (beginner friendly)
Numbered files above are the formal design record. The **topic folders** below explain the same ideas in simple Hinglish with small examples, from basic to advanced. Read them in this order if you are new.

| Folder | Kya seekhoge |
|---|---|
| `trading-basics/` | Spot, futures, leverage, liquidation, order types, candles |
| `risk-management/` | Stop loss, position size, drawdown, risk engine, kill-switch |
| `architecture-basics/` | Service, monolith vs microservices, API, data ownership |
| `java-spring-boot/` | Spring Boot kya hai, layers, scheduler, WebSocket |
| `python-fastapi/` | FastAPI, Pydantic, Python kyun ML ke liye |
| `databases/` | PostgreSQL, schema, migrations, Redis |
| `kafka-and-events/` | Event, topic, producer/consumer, Kafka kyun |
| `order-lifecycle/` | Order state machine, idempotency, reconciliation |
| `exchange-connector/` | Adapter pattern, Delta Exchange, API keys, rate limits |
| `backtesting-and-learning/` | Backtest, lookahead, overfitting, shadow/paper, model registry |
| `ai-llm-usage/` | LLM kahan use karna hai aur kahan nahi |
| `devops-basics/` | Docker, compose, CI, secrets, testnet vs live |
| `frontend-angular/` | Terminal UI, Angular basics, UI sirf gateway se baat karta hai |
