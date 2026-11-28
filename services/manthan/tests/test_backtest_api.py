import uuid
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any

import httpx2
import pytest
from fastapi.testclient import TestClient
from research_service.main import create_app
from research_service.settings import ResearchSettings
from sqlalchemy import text
from vajra_common.core_client import CoreClient
from vajra_common.db import create_engine_from
from vajra_common.migrations import run_migrations
from vajra_common.settings import DatabaseSettings

SERVICE_ROOT = Path(__file__).resolve().parents[1]
START = datetime(2026, 1, 1, tzinfo=UTC)
# fast=2 slow=3 closes 10,10,10,8,14,14,6,6 -> BUY fills candle 5 open, SELL fills candle 7 open (see test_backtester)
ROWS = [("10", "10"), ("10", "10"), ("10", "10"), ("10", "8"), ("8", "14"), ("15", "14"), ("14", "6"), ("7", "6")]
INSTRUMENT = {"symbol": "BTCUSD", "contractValue": "0.001", "tickSize": "0.5", "takerFee": "0.0005"}


def _iso(moment: datetime) -> str:
    return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _core(rows: list[tuple[str, str]] = ROWS, status: int = 200) -> CoreClient:
    def handler(request: httpx2.Request) -> httpx2.Response:
        if status >= 400:
            return httpx2.Response(status)
        if request.url.path == "/api/v1/instruments":
            return httpx2.Response(200, json=[INSTRUMENT])
        lo, hi = request.url.params["from"], request.url.params["to"]
        body = []
        for i, (o, c) in enumerate(rows):
            opened = START + timedelta(minutes=i)
            if lo <= _iso(opened) < hi:
                body.append(
                    {
                        "openTime": _iso(opened),
                        "open": o,
                        "high": max(o, c, key=Decimal),
                        "low": min(o, c, key=Decimal),
                        "close": c,
                        "volume": "1",
                    }
                )
        return httpx2.Response(200, json=body)

    return CoreClient("http://core", transport=httpx2.MockTransport(handler))


@pytest.fixture(scope="module")
def migrated(settings: DatabaseSettings) -> DatabaseSettings:
    run_migrations(SERVICE_ROOT / "alembic.ini", settings)
    return settings


def _client(settings: DatabaseSettings, core: CoreClient, now: datetime | None = None) -> TestClient:
    clock = (lambda: now) if now else None
    return TestClient(create_app(settings, ResearchSettings(core_base_url="http://core"), core, clock))


@pytest.fixture
def client(migrated: DatabaseSettings) -> Iterator[TestClient]:
    with _client(migrated, _core()) as c:
        yield c


def _body(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "symbol": "BTCUSD",
        "start": _iso(START),
        "end": _iso(START + timedelta(minutes=8)),
        "fast": 2,
        "slow": 3,
    }
    return body | overrides


def test_post_backtest_stores_and_get_returns_it(client: TestClient, migrated: DatabaseSettings) -> None:
    created = client.post("/api/v1/backtests", json=_body())

    assert created.status_code == 201
    body = created.json()
    [trade] = body["trades"]
    assert trade["entry_price"] == "15.0030"  # 15 * 1.0002, Decimal scale kept
    assert trade["exit_price"] == "6.9986"
    assert Decimal(trade["pnl"]) == Decimal("-0.0080154008")  # exact; string keeps trailing scale
    assert trade["closed_at_end"] is False
    assert body["params"]["fee_rate"] == "0.0005"  # resolved from the instrument taker fee
    assert Decimal(body["metrics"]["net_pnl"]) == Decimal("-0.0080154008")
    assert body["metrics"]["win_rate"] == "0"
    assert body["metrics"]["trade_count"] == 1

    fetched = client.get(f"/api/v1/backtests/{body['run_id']}")
    assert fetched.status_code == 200
    assert fetched.json() == body
    with create_engine_from(migrated).connect() as conn:
        assert (
            conn.execute(
                text("select count(*) from research.backtest_trade where run_id = :r"), {"r": body["run_id"]}
            ).scalar()
            == 1
        )


def test_backtest_rejects_too_few_candles(client: TestClient) -> None:
    response = client.post("/api/v1/backtests", json=_body(end=_iso(START + timedelta(minutes=4))))

    assert response.status_code == 422
    assert "not enough candles" in response.json()["detail"]


@pytest.mark.parametrize(
    "override",
    [
        {"end": _iso(START)},
        {"end": _iso(START + timedelta(days=181))},
        {"start": "2026-01-01T00:00:00"},
        {"fast": 5, "slow": 3},
        {"slippage_bps": "-1"},
        {"fee_rate": "-0.1"},
        {"symbol": "NOPE"},
    ],
)
def test_backtest_rejects_bad_request(client: TestClient, override: dict[str, Any]) -> None:
    assert client.post("/api/v1/backtests", json=_body(**override)).status_code == 422


def test_backtest_rejects_bad_range(client: TestClient) -> None:
    assert client.post("/api/v1/backtests", json=_body(start=_iso(START + timedelta(hours=1)))).status_code == 422


def test_get_unknown_run_404(client: TestClient) -> None:
    assert client.get(f"/api/v1/backtests/{uuid.uuid4()}").status_code == 404


def test_core_down_returns_503(migrated: DatabaseSettings) -> None:
    with _client(migrated, _core(status=503)) as c:
        assert c.post("/api/v1/backtests", json=_body()).status_code == 503


def test_core_4xx_returns_503(migrated: DatabaseSettings) -> None:
    with _client(migrated, _core(status=404)) as c:
        response = c.post("/api/v1/backtests", json=_body())

    assert response.status_code == 503
    assert "core-service" in response.json()["detail"]


def test_open_minute_excluded_when_end_in_future(migrated: DatabaseSettings) -> None:
    now = START + timedelta(minutes=7, seconds=30)  # candle 7 is still open
    with _client(migrated, _core(), now) as c:
        response = c.post("/api/v1/backtests", json=_body(end=_iso(START + timedelta(minutes=9))))

    body = response.json()
    clamped = START + timedelta(minutes=7)
    assert body["params"]["end"] == clamped.isoformat()
    assert body["metrics"]["candle_count"] == 6  # candles 0..5 (candle 6 is the live-edge snapshot, dropped)
    [trade] = body["trades"]
    assert trade["closed_at_end"] is True
    assert Decimal(trade["exit_price"]) == Decimal("14") * Decimal("0.9998")  # candle 5 close, slippage 2 bps


def test_live_edge_snapshot_candle_dropped(migrated: DatabaseSettings) -> None:
    now = START + timedelta(minutes=7, seconds=30)
    with _client(migrated, _core(), now) as c:
        edge = c.post("/api/v1/backtests", json=_body(end=_iso(START + timedelta(minutes=9)))).json()
        past = c.post("/api/v1/backtests", json=_body(end=_iso(START + timedelta(minutes=6)))).json()

    assert edge["metrics"]["candle_count"] == 6  # core returned candles 0..6; newest dropped
    assert past["metrics"]["candle_count"] == 6  # end before the live edge: candles 0..5 all kept
