import asyncio
import logging
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError
from signal_service.main import create_app
from signal_service.settings import HeartbeatSettings, StrategySettings
from signal_service.signal_log import SignalLog, SignalRow
from signal_service.sma_cross_runner import SmaCrossRunner
from sqlalchemy import Engine, text
from sqlalchemy.exc import IntegrityError
from vajra_common.core_client import CoreUnavailable
from vajra_common.db import create_engine_from
from vajra_common.migrations import run_migrations
from vajra_common.settings import DatabaseSettings
from vajra_strategies import Candle, SmaCross

SERVICE_ROOT = Path(__file__).resolve().parents[1]
NOW = datetime(2026, 1, 1, 12, 5, 30, tzinfo=UTC)
CROSS_UP = [5, 5, 5, 4, 7]
NO_CROSS = [5, 5, 5, 4, 4]


def _candles(closes: list[int], first_open: datetime) -> list[Candle]:
    return [
        Candle(first_open + timedelta(minutes=i), Decimal(c), Decimal(c), Decimal(c), Decimal(c), Decimal(1))
        for i, c in enumerate(closes)
    ]


def _at(minute: int, closes: list[int]) -> list[Candle]:
    """Candles ending at 12:<minute>, one per minute."""
    return _candles(closes, datetime(2026, 1, 1, 12, minute, tzinfo=UTC) - timedelta(minutes=len(closes) - 1))


def _live(minute: int, closes: list[int]) -> list[Candle]:
    """Like _at, plus the newest stored candle (a snapshot, value 0) one minute later; it must never be decided on."""
    return [*_at(minute, closes), *_candles([0], datetime(2026, 1, 1, 12, minute + 1, tzinfo=UTC))]


class FakeCore:
    def __init__(self, candles: list[Candle], position: int = 0, down: bool = False) -> None:
        self.candle_rows = candles
        self.position = position
        self.down = down
        self.venues: list[str] = []
        self.ranges: list[tuple[datetime, datetime]] = []

    def candles(self, symbol: str, start: datetime, end: datetime) -> list[Candle]:
        if self.down:
            raise CoreUnavailable("down")
        self.ranges.append((start, end))
        return self.candle_rows

    def position_size(self, venue: str, symbol: str) -> int:
        self.venues.append(venue)
        return self.position


class FakePublisher:
    def __init__(self) -> None:
        self.published: list[uuid.UUID] = []

    async def publish(self, row: SignalRow) -> None:
        self.published.append(row.signal_id)


@pytest.fixture
def engine(settings: DatabaseSettings) -> Iterator[Engine]:
    run_migrations(SERVICE_ROOT / "alembic.ini", settings)
    eng = create_engine_from(settings)
    with eng.begin() as conn:
        conn.execute(text("delete from signal.signal_log"))
    yield eng
    eng.dispose()


def _runner(
    engine: Engine, core: FakeCore, publisher: FakePublisher | None = None, mode: str = "PAPER"
) -> SmaCrossRunner:
    return SmaCrossRunner(
        SignalLog(engine),
        publisher or FakePublisher(),
        core,  # type: ignore[arg-type]
        SmaCross(fast=2, slow=3),
        StrategySettings(core_base_url="http://core:8081", sma_fast=2, sma_slow=3),
        mode,
        clock=lambda: NOW,
    )


def _rows(engine: Engine) -> list[tuple[str, str, str, datetime, bool]]:
    sql = text(
        "select strategy, symbol, side, candle_time, published_at is not null "
        "from signal.signal_log order by generated_at"
    )
    with engine.connect() as conn:
        return [tuple(r) for r in conn.execute(sql)]  # type: ignore[misc]


def test_buy_signal_published_on_cross_up(engine: Engine) -> None:
    candles = _at(4, CROSS_UP)
    publisher = FakePublisher()

    asyncio.run(_runner(engine, FakeCore(_live(4, CROSS_UP)), publisher).tick())

    assert _rows(engine) == [("sma_cross", "BTCUSD", "BUY", candles[-1].open_time, True)]
    assert len(publisher.published) == 1


def test_fetches_window_ending_now(engine: Engine) -> None:
    core = FakeCore(_live(4, CROSS_UP))

    asyncio.run(_runner(engine, core).tick())

    assert core.ranges == [(NOW - timedelta(minutes=11), NOW)]


def test_open_candle_is_excluded(engine: Engine) -> None:
    closed = _at(4, CROSS_UP)
    open_candle = _candles([0], datetime(2026, 1, 1, 12, 5, tzinfo=UTC))  # would cancel the cross

    asyncio.run(_runner(engine, FakeCore(closed + open_candle)).tick())

    assert [r[2] for r in _rows(engine)] == ["BUY"]


def test_cross_only_in_open_candle_does_nothing(engine: Engine) -> None:
    closed = _at(4, NO_CROSS)
    open_candle = _candles([7], datetime(2026, 1, 1, 12, 5, tzinfo=UTC))

    asyncio.run(_runner(engine, FakeCore(closed + open_candle)).tick())

    assert _rows(engine) == []


def test_newest_stored_candle_is_never_decided_on(engine: Engine) -> None:
    closed_by_clock = _at(4, CROSS_UP)  # newest opens 12:04, closed at 12:05 < now, but may be a mid-minute snapshot
    asyncio.run(_runner(engine, FakeCore(closed_by_clock)).tick())
    assert _rows(engine) == []

    next_candle = _candles([0], datetime(2026, 1, 1, 12, 5, tzinfo=UTC))
    asyncio.run(_runner(engine, FakeCore(closed_by_clock + next_candle)).tick())
    assert [(r[2], r[3]) for r in _rows(engine)] == [("BUY", closed_by_clock[-1].open_time)]


def test_stale_candles_do_nothing(engine: Engine) -> None:
    # newest final candle opens 12:01 (12:02 is the dropped snapshot); now 12:05:30 is more than 4 min later
    asyncio.run(_runner(engine, FakeCore(_live(1, CROSS_UP))).tick())

    assert _rows(engine) == []


def test_core_down_skips_tick(engine: Engine) -> None:
    asyncio.run(_runner(engine, FakeCore([], down=True)).tick())

    assert _rows(engine) == []


def test_no_second_signal_for_same_candle(engine: Engine) -> None:
    core = FakeCore(_live(4, CROSS_UP))
    runner = _runner(engine, core)

    asyncio.run(runner.tick())
    asyncio.run(runner.tick())
    asyncio.run(_runner(engine, core).tick())  # restart

    assert len(_rows(engine)) == 1


def test_unique_index_is_backstop_when_guard_is_raced(engine: Engine, monkeypatch: pytest.MonkeyPatch) -> None:
    core = FakeCore(_live(4, CROSS_UP))
    asyncio.run(_runner(engine, core).tick())
    monkeypatch.setattr(SignalLog, "has_signal_for_candle", lambda *_: False)
    publisher = FakePublisher()

    asyncio.run(_runner(engine, core, publisher).tick())

    assert len(_rows(engine)) == 1
    assert publisher.published == []


def test_unpublished_rows_round_trip_with_candle_time(engine: Engine) -> None:
    candles = _at(4, CROSS_UP)
    asyncio.run(_runner(engine, FakeCore(_live(4, CROSS_UP))).tick())
    with engine.begin() as conn:
        conn.execute(text("update signal.signal_log set published_at = null"))

    (row,) = SignalLog(engine).unpublished(10)

    assert row.candle_time == candles[-1].open_time


def test_position_read_for_trading_mode_venue(engine: Engine) -> None:
    core = FakeCore(_live(4, CROSS_UP))

    asyncio.run(_runner(engine, core, mode="DELTA_TESTNET").tick())

    assert core.venues == ["DELTA_TESTNET"]


def test_core_base_url_required_for_sma_cross() -> None:
    with pytest.raises(ValidationError):
        StrategySettings()
    assert StrategySettings(strategy="none").core_base_url is None


def test_strategy_none_starts_no_task(settings: DatabaseSettings, monkeypatch: pytest.MonkeyPatch) -> None:
    def _forbidden(*_: object, **__: object) -> None:
        raise AssertionError("no runner may be constructed for strategy none")

    monkeypatch.setattr("signal_service.main.SmaCrossRunner", _forbidden)
    monkeypatch.setattr("signal_service.main.Heartbeat", _forbidden)
    unreachable = HeartbeatSettings(kafka_bootstrap="localhost:1")

    with TestClient(create_app(settings, unreachable, StrategySettings(strategy="none"))) as client:
        assert client.get("/health").status_code == 200


def _outbox_row(engine: Engine, candle_time: datetime) -> SignalRow:
    row = SignalRow(uuid.uuid4(), "sma_cross", "BTCUSD", "BUY", "MARKET", 1, candle_time, candle_time)
    SignalLog(engine).insert(row)
    return row


def _expired(engine: Engine) -> list[bool]:
    with engine.connect() as conn:
        return [r[0] for r in conn.execute(text("select expired_at is not null from signal.signal_log"))]


def test_stale_outbox_signal_is_expired_not_published(engine: Engine, caplog: pytest.LogCaptureFixture) -> None:
    row = _outbox_row(engine, NOW - timedelta(minutes=10))
    publisher = FakePublisher()

    with caplog.at_level(logging.WARNING):
        asyncio.run(_runner(engine, FakeCore([], down=True), publisher).tick())

    assert publisher.published == []
    assert _expired(engine) == [True]
    assert SignalLog(engine).unpublished(10) == []
    assert str(row.signal_id) in caplog.text


def test_fresh_outbox_signal_is_republished(engine: Engine) -> None:
    row = _outbox_row(engine, NOW - timedelta(minutes=1))
    publisher = FakePublisher()

    asyncio.run(_runner(engine, FakeCore([], down=True), publisher).tick())

    assert publisher.published == [row.signal_id]
    assert _expired(engine) == [False]


def test_old_heartbeat_row_expired_by_sma_runner(engine: Engine) -> None:
    row = SignalRow(uuid.uuid4(), "heartbeat", "BTCUSD", "BUY", "MARKET", 1, NOW - timedelta(days=5))
    SignalLog(engine).insert(row)
    publisher = FakePublisher()

    asyncio.run(_runner(engine, FakeCore([], down=True), publisher).tick())

    assert publisher.published == []
    assert _expired(engine) == [True]


def test_other_integrity_errors_are_not_swallowed(engine: Engine, monkeypatch: pytest.MonkeyPatch) -> None:
    taken = uuid.uuid4()
    SignalLog(engine).insert(SignalRow(taken, "heartbeat", "BTCUSD", "BUY", "MARKET", 1, NOW))
    monkeypatch.setattr("signal_service.sma_cross_runner.uuid4", lambda: taken)

    with pytest.raises(IntegrityError):
        asyncio.run(_runner(engine, FakeCore(_live(4, CROSS_UP))).tick())
