import json
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any

import httpx2
from vajra_strategies import Candle

_WINDOW = timedelta(days=1)


class CoreUnavailable(Exception):
    """core-service could not be reached or answered with a 5xx."""


@dataclass(frozen=True)
class InstrumentInfo:
    symbol: str
    contract_value: Decimal
    tick_size: Decimal
    taker_fee: Decimal


def _iso(moment: datetime) -> str:
    return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class CoreClient:
    """Read-only REST client for core-service data (other services may not read core tables)."""

    def __init__(
        self,
        base_url: str,
        timeout_seconds: float = 5.0,
        transport: httpx2.BaseTransport | None = None,
    ) -> None:
        self._http = httpx2.Client(base_url=base_url, timeout=timeout_seconds, transport=transport)

    def candles(self, symbol: str, start: datetime, end: datetime) -> list[Candle]:
        if start.tzinfo is None or end.tzinfo is None:
            raise ValueError("start and end must be timezone-aware")
        result: list[Candle] = []
        window_start = start
        while window_start < end:
            window_end = min(window_start + _WINDOW, end)
            params = {"symbol": symbol, "from": _iso(window_start), "to": _iso(window_end)}
            result.extend(_to_candle(row) for row in self._get("/api/v1/candles", params))
            window_start = window_end
        return result

    def position_size(self, venue: str, symbol: str) -> int:
        for row in self._get("/api/v1/positions"):
            if row["venue"] == venue and row["symbol"] == symbol:
                return int(row["size"])
        return 0

    def instrument(self, symbol: str) -> InstrumentInfo:
        for row in self._get("/api/v1/instruments"):
            if row["symbol"] == symbol:
                return InstrumentInfo(
                    symbol=symbol,
                    contract_value=Decimal(row["contractValue"]),
                    tick_size=Decimal(row["tickSize"]),
                    taker_fee=Decimal(row["takerFee"]),
                )
        raise KeyError(symbol)

    def close(self) -> None:
        self._http.close()

    def _get(self, path: str, params: dict[str, str] | None = None) -> list[dict[str, Any]]:
        try:
            response = self._http.get(path, params=params)
        except httpx2.TransportError as error:
            raise CoreUnavailable(f"core-service unreachable: {error}") from error
        if response.status_code >= 500:
            raise CoreUnavailable(f"core-service returned {response.status_code} for {path}")
        response.raise_for_status()
        try:
            return json.loads(response.text, parse_float=Decimal)
        except json.JSONDecodeError as error:
            raise CoreUnavailable(f"core-service returned invalid JSON for {path}") from error


def _to_candle(row: dict[str, Any]) -> Candle:
    return Candle(
        open_time=datetime.fromisoformat(row["openTime"]),
        open=Decimal(row["open"]),
        high=Decimal(row["high"]),
        low=Decimal(row["low"]),
        close=Decimal(row["close"]),
        volume=Decimal(row["volume"]),
    )
