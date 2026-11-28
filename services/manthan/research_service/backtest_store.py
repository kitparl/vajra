import json
from dataclasses import dataclass
from datetime import UTC
from decimal import Decimal
from typing import Any
from uuid import UUID

from sqlalchemy import Engine, text

from research_service.backtester import Trade


@dataclass(frozen=True)
class StoredRun:
    run_id: UUID
    params: dict[str, Any]
    metrics: dict[str, Any]
    trades: list[Trade]


def plain(value: Decimal) -> str:
    """Decimal as a plain string (never scientific notation) so JSON keeps the exact value."""
    return format(value, "f")


def trade_json(seq: int, trade: Trade) -> dict[str, Any]:
    return {
        "seq": seq,
        "side": trade.side,
        "entry_time": trade.entry_time.astimezone(UTC).isoformat(),
        "entry_price": plain(trade.entry_price),
        "exit_time": trade.exit_time.astimezone(UTC).isoformat(),
        "exit_price": plain(trade.exit_price),
        "fees": plain(trade.fees),
        "pnl": plain(trade.pnl),
        "closed_at_end": trade.closed_at_end,
    }


class BacktestStore:
    """Tables research.backtest_run and research.backtest_trade."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def save(
        self, run_id: UUID, strategy: str, params: dict[str, Any], metrics: dict[str, Any], trades: list[Trade]
    ) -> None:
        run_sql = text(
            "insert into research.backtest_run (run_id, strategy, params, metrics) "
            "values (:run_id, :strategy, cast(:params as jsonb), cast(:metrics as jsonb))"
        )
        trade_sql = text(
            "insert into research.backtest_trade "
            "(run_id, seq, side, entry_time, entry_price, exit_time, exit_price, fees, pnl, closed_at_end) "
            "values (:run_id, :seq, :side, :entry_time, :entry_price, :exit_time, :exit_price, "
            ":fees, :pnl, :closed_at_end)"
        )
        rows = [
            {
                "run_id": run_id,
                "seq": seq,
                "side": t.side,
                "entry_time": t.entry_time,
                "entry_price": t.entry_price,
                "exit_time": t.exit_time,
                "exit_price": t.exit_price,
                "fees": t.fees,
                "pnl": t.pnl,
                "closed_at_end": t.closed_at_end,
            }
            for seq, t in enumerate(trades)
        ]
        with self._engine.begin() as conn:
            conn.execute(
                run_sql,
                {"run_id": run_id, "strategy": strategy, "params": json.dumps(params), "metrics": json.dumps(metrics)},
            )
            if rows:
                conn.execute(trade_sql, rows)

    def get(self, run_id: UUID) -> StoredRun | None:
        with self._engine.connect() as conn:
            run = conn.execute(
                text("select params, metrics from research.backtest_run where run_id = :id"), {"id": run_id}
            ).one_or_none()
            if run is None:
                return None
            rows = conn.execute(
                text(
                    "select side, entry_time, entry_price, exit_time, exit_price, fees, pnl, closed_at_end "
                    "from research.backtest_trade where run_id = :id order by seq"
                ),
                {"id": run_id},
            ).all()
        trades = [Trade(*row) for row in rows]
        return StoredRun(run_id, run.params, run.metrics, trades)
