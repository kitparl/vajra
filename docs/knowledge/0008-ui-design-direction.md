---
number: 0008
title: UI design direction
type: design
created: 2026-11-21
status: current
---

# UI design direction

**Goal:** a modern trading terminal, inspired by the speed and density of Zerodha Kite, the clarity and calm of Groww, and the chart quality of the best trading platforms. Inspired, not cloned: no copying of logos, icons, names or distinctive trade dress.

## Principles
- **Dense but readable:** compact tables, tabular numerals, clear hierarchy. A density toggle (comfortable / compact).
- **Fast and live:** WebSocket streaming, subtle price flash on change, no layout jumps.
- **Calm by default:** restrained green/red used only for price direction and P&L; neutral surfaces; no decoration for its own sake.
- **Keyboard-first:** shortcuts for search (`/`), buy/sell, switching panels, kill-switch confirm flow.
- **Dark-first with a light theme**, both from the same design tokens.
- **Accessible:** never rely on color alone (use +/- signs and arrows), sufficient contrast, focus states.
- **Responsive:** full terminal on desktop, focused cards and lists on mobile.

## Layout
- Left: watchlists with search and quick actions.
- Center: chart workspace (candles, indicators, drawing tools, order markers, multiple timeframes).
- Right/dock: order ticket, positions, orders, fills, depth.
- Top bar: connection health per exchange, mode badge (TESTNET / PAPER / LIVE), global P&L, kill-switch.

## Screens
Dashboard, Markets and watchlists, Terminal (chart + order ticket), Orders and fills, Positions and P&L, Strategies (status: shadow / paper / live), Models (registry, champion vs challenger, drift), Backtests, Risk (limits, breaches, kill-switch), Exchanges (connector health), Alerts, Audit log, Settings.

## Safety in the UI
- Always show the environment badge; live mode uses a distinct accent.
- Destructive actions (kill-switch, enable live, raise limits) need confirmation and are audited.
- Limits can be viewed in the UI; changing risk limits requires an explicit, logged action.

## Implementation notes
- Angular 22: signals, zoneless-friendly code, OnPush by default, Signal Forms.
- Design tokens (color, spacing, type, radius) in one place; build a small component library on Angular CDK rather than adopting a heavy theme.
- Charts: a lightweight financial charting library for candles (for example TradingView's open-source Lightweight Charts) plus a general chart library for analytics. Confirm licensing before adoption.
- UI talks only to `gateway-bff`.
