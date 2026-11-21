---
number: 0007
title: Exchange connector design (Delta first)
type: design
created: 2026-11-21
status: current
---

# Exchange connector design

## Connector interface (in core-service)
Each exchange is one adapter that implements:
- **Market data:** subscribe/unsubscribe tickers, candles, order book; backfill history.
- **Trading:** place, amend, cancel orders; query open orders and order history.
- **Account:** balances, positions, leverage and margin settings.
- **Metadata:** instruments (tick size, lot size, contract type, fees), trading hours.
- **Operational:** rate-limit policy, error normalization, health, reconnect strategy.

Common domain model: `Instrument`, `Order`, `Fill`, `Position`, `Balance`. Perpetuals, futures, options, spot and (later) equities must fit it. Exchange specifics (symbol mapping, fee model, margin rules) stay inside the adapter.

## How to add a new exchange
1. Map instruments and symbols to the common model.
2. Implement the interface and error mapping.
3. Add a fee and rate-limit profile.
4. Run the shared connector test suite against a simulated exchange and the exchange's testnet.
5. Run paper trading for a while before any live use.

A **simulated exchange adapter** is built first. A second real exchange is added early, to prove the abstraction is not Delta-shaped.

## Delta Exchange notes (checked 2026-11-21 against docs.delta.exchange and the exchange's API page)
- India and Global are separate: India production API is `https://api.india.delta.exchange`; the Global API `https://api.delta.exchange` cannot be used with India keys.
- India testnet REST: `https://cdn-ind.testnet.deltaex.org`. Keys created in a production India account work only with production; keys from a demo account work only with testnet.
- WebSocket (India): public `wss://public-socket.india.delta.exchange`, private `wss://socket.india.delta.exchange`; testnet has its own socket URLs.
- API keys have separate permissions (Read Data, Trading) and can be IP-whitelisted.
- Their data centers are in AWS Tokyo, which matters for server placement.
- Trading and data APIs are free; a testnet exists. Product ids are needed for most calls (fetch the product list first).
- Always confirm the current docs and changelog before implementing; this section is a snapshot.

## Stocks later (India)
SEBI's retail algo framework applies to API trading through brokers (exchange-assigned Algo-ID on orders, broker-side compliance). Treat each broker as a connector and re-check current rules before starting.
