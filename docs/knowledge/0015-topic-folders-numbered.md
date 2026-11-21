---
number: 0015
title: Topic folders get a number prefix
type: decision
created: 2026-11-21
status: current
---

# Topic folders get a number prefix

## Decision
Hinglish topic folders under `docs/knowledge/` are named `NN-topic/` (for example `01-trading-basics/`, `12-devops-basics/`). The number is the reading order for a beginner.

## Why
Without a prefix, the file system and IDE list the folders alphabetically, so the intended reading order existed only in the `README.md` table. New topics also had no defined place in that order.

## Rules
- A new topic folder takes the **next free two-digit number** and is appended to the end of the topic table in `docs/knowledge/README.md`.
- Numbers are never renumbered or reused (same idea as the global `NNNN` files).
- Files inside a folder keep their own `NN-slug.md` sequence.
- The `topic:` field in a note's frontmatter stays the plain slug (for example `topic: trading-basics`), without the number.
- Cross-links between topic notes use the full folder name (`../02-risk-management/`).

## Initial numbering (2026-11-21)
Taken from the existing README reading order: 01 trading-basics, 02 risk-management, 03 architecture-basics, 04 java-spring-boot, 05 python-fastapi, 06 databases, 07 kafka-and-events, 08 order-lifecycle, 09 exchange-connector, 10 backtesting-and-learning, 11 ai-llm-usage, 12 devops-basics, 13 frontend-angular.
