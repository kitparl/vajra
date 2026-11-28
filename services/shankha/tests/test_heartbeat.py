import asyncio
import logging
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from pydantic import ValidationError
from signal_service.heartbeat import Heartbeat, next_side
from signal_service.outbox import republish_pending
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


def _old_row(engine: Engine, age: timedelta) -> SignalRow:
    row = SignalRow(uuid.uuid4(), "heartbeat", "BTCUSD", "SELL", "MARKET", 1, datetime.now(UTC) - age)
    SignalLog(engine).insert(row)
    return row


def _expired(engine: Engine, signal_id: uuid.UUID) -> bool:
    with engine.connect() as conn:
        return bool(
            conn.execute(
                text("select expired_at is not null from signal.signal_log where signal_id = :i"), {"i": signal_id}
            ).scalar()
        )


def test_heartbeat_expires_its_own_stale_rows(engine: Engine, caplog: pytest.LogCaptureFixture) -> None:
    row = _old_row(engine, timedelta(minutes=10))
    publisher = FakePublisher()
    heartbeat, _ = _heartbeat(engine, publisher)

    with caplog.at_level(logging.WARNING):
        asyncio.run(heartbeat.tick())

    assert str(row.signal_id) in caplog.text

    assert row.signal_id not in publisher.published
    assert _expired(engine, row.signal_id)


def test_fresh_heartbeat_row_is_republished(engine: Engine) -> None:
    row = _old_row(engine, timedelta(minutes=1))
    publisher = FakePublisher()
    heartbeat, _ = _heartbeat(engine, publisher)

    asyncio.run(heartbeat.tick())

    assert row.signal_id in publisher.published
    assert not _expired(engine, row.signal_id)


NOW = datetime(2026, 1, 1, 12, 0, tzinfo=UTC)


def _row_at(engine: Engine, age: timedelta, candle_age: timedelta | None = None) -> SignalRow:
    row = SignalRow(
        uuid.uuid4(),
        "sma_cross",
        "BTCUSD",
        "BUY",
        "MARKET",
        1,
        NOW - age,
        None if candle_age is None else NOW - candle_age,
    )
    SignalLog(engine).insert(row)
    return row


def _republish(engine: Engine, publisher: FakePublisher, clock: object = lambda: NOW) -> None:
    asyncio.run(republish_pending(SignalLog(engine), publisher, clock))  # type: ignore[arg-type]


def test_row_expires_if_clock_passes_limit_during_republish(engine: Engine) -> None:
    now = [NOW]

    class SlowPublisher(FakePublisher):
        async def publish(self, row: SignalRow) -> None:
            await super().publish(row)
            now[0] += timedelta(minutes=1)

    rows = [_row_at(engine, timedelta(minutes=3, seconds=30) - timedelta(milliseconds=i)) for i in range(3)]
    publisher = SlowPublisher()

    _republish(engine, publisher, lambda: now[0])

    assert publisher.published == [rows[0].signal_id]
    assert [_expired(engine, r.signal_id) for r in rows] == [False, True, True]


def test_row_just_inside_age_limit_is_published(engine: Engine) -> None:
    row = _row_at(engine, timedelta(minutes=3, seconds=50))
    publisher = FakePublisher()
    _republish(engine, publisher)
    assert publisher.published == [row.signal_id]


def test_row_just_past_age_limit_is_expired(engine: Engine) -> None:
    row = _row_at(engine, timedelta(minutes=4, seconds=10))
    publisher = FakePublisher()
    _republish(engine, publisher)
    assert publisher.published == []
    assert _expired(engine, row.signal_id)


def test_candle_time_wins_over_fresh_generated_at(engine: Engine) -> None:
    row = _row_at(engine, timedelta(seconds=1), candle_age=timedelta(minutes=5))
    publisher = FakePublisher()
    _republish(engine, publisher)
    assert publisher.published == []
    assert _expired(engine, row.signal_id)
