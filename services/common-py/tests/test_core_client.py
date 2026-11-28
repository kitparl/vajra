from datetime import UTC, datetime, timedelta
from decimal import Decimal

import httpx2
import pytest
from vajra_common.core_client import CoreClient, CoreUnavailable


def _client(handler: httpx2.MockTransport | object) -> CoreClient:
    return CoreClient("http://core", transport=httpx2.MockTransport(handler))  # type: ignore[arg-type]


def _candle_json(open_time: str) -> dict[str, str]:
    return {
        "symbol": "BTCUSD",
        "openTime": open_time,
        "open": "61234.5",
        "high": "61240.0",
        "low": "61200.0",
        "close": "61234.5",
        "volume": "10.0",
    }


def test_candles_split_into_day_windows() -> None:
    start = datetime(2026, 11, 1, tzinfo=UTC)
    end = start + timedelta(days=2, hours=12)
    requests: list[httpx2.Request] = []

    def handler(request: httpx2.Request) -> httpx2.Response:
        requests.append(request)
        return httpx2.Response(200, json=[_candle_json(request.url.params["from"])])

    candles = _client(handler).candles("BTCUSD", start, end)

    assert [r.url.params["from"] for r in requests] == [
        "2026-11-01T00:00:00Z",
        "2026-11-02T00:00:00Z",
        "2026-11-03T00:00:00Z",
    ]
    assert [r.url.params["to"] for r in requests] == [
        "2026-11-02T00:00:00Z",
        "2026-11-03T00:00:00Z",
        "2026-11-03T12:00:00Z",
    ]
    assert all(r.url.params["symbol"] == "BTCUSD" for r in requests)
    assert [c.open_time for c in candles] == [
        datetime(2026, 11, 1, tzinfo=UTC),
        datetime(2026, 11, 2, tzinfo=UTC),
        datetime(2026, 11, 3, tzinfo=UTC),
    ]
    assert candles[0].close == Decimal("61234.5")
    assert isinstance(candles[0].close, Decimal)


def test_numeric_json_values_never_become_float() -> None:
    body = '[{"openTime":"2026-11-01T00:00:00Z","open":61234.5,"high":1.5,"low":1.5,"close":61234.5,"volume":2}]'

    def handler(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(200, text=body)

    start = datetime(2026, 11, 1, tzinfo=UTC)
    candle = _client(handler).candles("BTCUSD", start, start + timedelta(hours=1))[0]

    assert candle.open == Decimal("61234.5")


def test_position_size_zero_when_absent() -> None:
    client = _client(lambda r: httpx2.Response(200, json=[]))

    assert client.position_size("PAPER", "BTCUSD") == 0


def test_position_size_reads_venue_and_symbol() -> None:
    rows = [
        {"venue": "DELTA_TESTNET", "symbol": "BTCUSD", "size": 3, "avgEntryPrice": "0"},
        {"venue": "PAPER", "symbol": "BTCUSD", "size": -1, "avgEntryPrice": "0"},
        {"venue": "PAPER", "symbol": "ETHUSD", "size": 7, "avgEntryPrice": "0"},
    ]
    client = _client(lambda r: httpx2.Response(200, json=rows))

    assert client.position_size("PAPER", "BTCUSD") == -1
    assert client.position_size("DELTA_TESTNET", "BTCUSD") == 3


def test_instrument_returns_info() -> None:
    rows = [
        {
            "venue": "DELTA_TESTNET",
            "symbol": "BTCUSD",
            "contractValue": "0.001",
            "tickSize": "0.1",
            "takerFee": "0.0005",
        }
    ]
    info = _client(lambda r: httpx2.Response(200, json=rows)).instrument("BTCUSD")

    assert info.symbol == "BTCUSD"
    assert info.contract_value == Decimal("0.001")
    assert info.tick_size == Decimal("0.1")
    assert info.taker_fee == Decimal("0.0005")


def test_instrument_unknown_raises_key_error() -> None:
    client = _client(lambda r: httpx2.Response(200, json=[]))

    with pytest.raises(KeyError):
        client.instrument("NOPE")


def test_server_error_raises_core_unavailable() -> None:
    client = _client(lambda r: httpx2.Response(503))

    with pytest.raises(CoreUnavailable):
        client.position_size("PAPER", "BTCUSD")


def test_connect_error_raises_core_unavailable() -> None:
    def handler(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.ConnectError("refused", request=request)

    with pytest.raises(CoreUnavailable):
        _client(handler).instrument("BTCUSD")


def test_client_error_is_not_core_unavailable() -> None:
    client = _client(lambda r: httpx2.Response(400))

    with pytest.raises(httpx2.HTTPStatusError):
        client.position_size("PAPER", "BTCUSD")


def test_read_error_raises_core_unavailable() -> None:
    def handler(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.ReadError("connection reset", request=request)

    with pytest.raises(CoreUnavailable):
        _client(handler).position_size("PAPER", "BTCUSD")


def test_invalid_json_raises_core_unavailable() -> None:
    client = _client(lambda r: httpx2.Response(200, text="<html>not json"))

    with pytest.raises(CoreUnavailable):
        client.position_size("PAPER", "BTCUSD")


def test_naive_datetime_rejected() -> None:
    client = _client(lambda r: httpx2.Response(200, json=[]))

    with pytest.raises(ValueError, match="timezone"):
        client.candles("BTCUSD", datetime(2026, 11, 1), datetime(2026, 11, 2, tzinfo=UTC))
