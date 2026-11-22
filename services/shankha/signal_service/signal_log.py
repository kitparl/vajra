from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

from sqlalchemy import Engine, text


@dataclass(frozen=True)
class SignalRow:
    signal_id: UUID
    strategy: str
    symbol: str
    side: str
    order_type: str
    size: int
    generated_at: datetime


class SignalLog:
    """Table signal.signal_log: every signal is stored first, then published (outbox)."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def last_side(self, strategy: str) -> str | None:
        sql = text("select side from signal.signal_log where strategy = :s order by generated_at desc limit 1")
        with self._engine.connect() as conn:
            return conn.execute(sql, {"s": strategy}).scalar()

    def insert(self, signal: SignalRow) -> None:
        sql = text(
            "insert into signal.signal_log (signal_id, strategy, symbol, side, order_type, size, generated_at) "
            "values (:signal_id, :strategy, :symbol, :side, :order_type, :size, :generated_at)"
        )
        with self._engine.begin() as conn:
            conn.execute(sql, vars(signal))

    def unpublished(self, limit: int) -> list[SignalRow]:
        sql = text(
            "select signal_id, strategy, symbol, side, order_type, size, generated_at from signal.signal_log "
            "where published_at is null order by generated_at limit :limit"
        )
        with self._engine.connect() as conn:
            return [SignalRow(*row) for row in conn.execute(sql, {"limit": limit})]

    def mark_published(self, signal_id: UUID, at: datetime) -> None:
        sql = text("update signal.signal_log set published_at = :at where signal_id = :id")
        with self._engine.begin() as conn:
            conn.execute(sql, {"at": at, "id": signal_id})
