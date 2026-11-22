import asyncio
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime
from pathlib import Path

import pytest
from pydantic import ValidationError
from signal_service.heartbeat import Heartbeat, next_side
from signal_service.settings import HeartbeatSettings
from signal_service.signal_log import SignalLog, SignalRow
from sqlalchemy import Engine, text
from vajra_common.db import create_engine_from
from vajra_common.migrations import run_migrations
from vajra_common.settings import DatabaseSettings

SERVICE_ROOT = Path(__file__).resolve().parents[1]


class FakePublisher:
    def __init__(self, failures: int = 0) -> None:
        self.failures = failures
        self.published: list[uuid.UUID] = []

    async def publish(self, row: SignalRow) -> None:
        if self.failures > 0:
            self.failures -= 1
            raise RuntimeError("broker down")
        self.published.append(row.signal_id)


@pytest.fixture
def engine(settings: DatabaseSettings) -> Iterator[Engine]:
    run_migrations(SERVICE_ROOT / "alembic.ini", settings)
    eng = create_engine_from(settings)
    with eng.begin() as conn:
        conn.execute(text("delete from signal.signal_log"))
    yield eng
    eng.dispose()


def _heartbeat(engine: Engine, publisher: FakePublisher) -> tuple[Heartbeat, SignalLog]:
    log = SignalLog(engine)
    return Heartbeat(log, publisher, HeartbeatSettings(kafka_bootstrap="unused:9092")), log


def _rows(engine: Engine) -> list[tuple[str, bool]]:
    with engine.connect() as conn:
        result = conn.execute(
            text("select side, published_at is not null from signal.signal_log order by generated_at")
        )
        return [(side, published) for side, published in result]


def test_next_side_alternates() -> None:
    assert next_side(None) == "BUY"
    assert next_side("BUY") == "SELL"
    assert next_side("SELL") == "BUY"


def test_next_side_continues_after_restart(engine: Engine) -> None:
    SignalLog(engine).insert(
        SignalRow(uuid.uuid4(), "heartbeat", "BTCUSD", "BUY", "MARKET", 1, datetime.now(UTC)),
    )
    heartbeat, _ = _heartbeat(engine, FakePublisher())

    asyncio.run(heartbeat.tick())

    assert [side for side, _ in _rows(engine)] == ["BUY", "SELL"]


def test_failed_publish_is_retried_next_tick(engine: Engine) -> None:
    publisher = FakePublisher(failures=1)
    heartbeat, _ = _heartbeat(engine, publisher)

    asyncio.run(heartbeat.tick())
    assert _rows(engine) == [("BUY", False)]

    asyncio.run(heartbeat.tick())
    assert [published for _, published in _rows(engine)] == [True, True]
    with engine.connect() as conn:
        ids = [r[0] for r in conn.execute(text("select signal_id from signal.signal_log order by generated_at"))]
    assert publisher.published == ids


def test_rejects_live_trading_mode(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TRADING_MODE", "LIVE")
    monkeypatch.setenv("KAFKA_BOOTSTRAP", "unused:9092")

    with pytest.raises(ValidationError):
        HeartbeatSettings()


def test_row_has_spec_values(engine: Engine) -> None:
    heartbeat, log = _heartbeat(engine, FakePublisher(failures=1))

    asyncio.run(heartbeat.tick())

    (row,) = log.unpublished(10)
    assert row.strategy == "heartbeat"
    assert row.symbol == "BTCUSD"
    assert row.size == 1
    assert row.order_type == "MARKET"
    assert isinstance(row.signal_id, uuid.UUID)
