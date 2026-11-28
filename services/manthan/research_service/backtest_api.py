from collections.abc import Callable
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Annotated, Any
from uuid import UUID, uuid4

import httpx2
from fastapi import APIRouter, HTTPException
from pydantic import AwareDatetime, BaseModel, Field, model_validator
from vajra_common.core_client import CoreClient, CoreUnavailable
from vajra_strategies import SmaCross

from research_service.backtest_store import BacktestStore, StoredRun, plain, trade_json
from research_service.backtester import BacktestParams, BacktestResult, NotEnoughCandles, run_backtest

MAX_RANGE = timedelta(days=180)
NonNegative = Annotated[Decimal, Field(ge=0, allow_inf_nan=False)]
Bps = Annotated[Decimal, Field(ge=0, le=10_000, allow_inf_nan=False)]


class BacktestRequest(BaseModel):
    symbol: str = Field(min_length=1)
    start: AwareDatetime
    end: AwareDatetime
    fast: int = 20
    slow: int = 60
    slippage_bps: Bps = Decimal("2")
    fee_rate: NonNegative | None = None

    @model_validator(mode="after")
    def _valid_range(self) -> BacktestRequest:
        if self.start >= self.end:
            raise ValueError("start must be before end")
        if self.end - self.start > MAX_RANGE:
            raise ValueError(f"range must not exceed {MAX_RANGE.days} days")
        return self

    def to_params(self) -> BacktestParams:
        return BacktestParams(self.symbol, self.start, self.end, self.fast, self.slow, self.slippage_bps, self.fee_rate)


def _params_json(params: BacktestParams, fee_rate: Decimal) -> dict[str, Any]:
    """Params as run (fee_rate resolved), Decimals as strings."""
    return {
        "symbol": params.symbol,
        "start": params.start.isoformat(),
        "end": params.end.isoformat(),
        "fast": params.fast,
        "slow": params.slow,
        "slippage_bps": plain(params.slippage_bps),
        "fee_rate": plain(fee_rate),
    }


def _metrics_json(result: BacktestResult) -> dict[str, Any]:
    return {
        "trade_count": len(result.trades),
        "candle_count": result.candle_count,
        "missing_minutes": result.missing_minutes,
        "gross_pnl": plain(result.gross_pnl),
        "fees": plain(result.fees),
        "net_pnl": plain(result.net_pnl),
        "max_drawdown": plain(result.max_drawdown),
        "win_rate": None if result.win_rate is None else plain(result.win_rate),
        "funding_included": result.funding_included,
    }


def _run_json(run: StoredRun) -> dict[str, Any]:
    return {
        "run_id": str(run.run_id),
        "params": run.params,
        "metrics": run.metrics,
        "trades": [trade_json(seq, t) for seq, t in enumerate(run.trades)],
    }


def utc_now() -> datetime:
    return datetime.now(UTC)


def backtest_router(core: CoreClient, store: BacktestStore, clock: Callable[[], datetime] = utc_now) -> APIRouter:
    router = APIRouter(prefix="/api/v1/backtests")

    @router.post("", status_code=201)
    def create_backtest(request: BacktestRequest) -> dict[str, Any]:
        params = request.to_params()
        # never include the still-open current minute: its close is not final and a re-run would differ
        current_minute = clock().replace(second=0, microsecond=0)
        reaches_live_edge = params.end >= current_minute
        end = min(params.end, current_minute)
        if end <= params.start:
            raise HTTPException(422, detail="range contains no closed candles")
        params = replace(params, end=end)
        try:
            strategy = SmaCross(params.fast, params.slow)
        except ValueError as error:
            raise HTTPException(422, detail=f"invalid strategy params: {error}") from error
        try:
            info = core.instrument(params.symbol)
        except KeyError as error:
            raise HTTPException(422, detail=f"unknown symbol: {params.symbol}") from error
        except (CoreUnavailable, httpx2.HTTPStatusError) as error:
            raise HTTPException(503, detail=f"core-service error: {error}") from error
        try:
            candles = core.candles(params.symbol, params.start, params.end)
        except (CoreUnavailable, httpx2.HTTPStatusError) as error:
            raise HTTPException(503, detail=f"core-service error: {error}") from error
        if reaches_live_edge:
            # core re-fetches only the newest stored candle, so at the live edge it may still be a snapshot
            candles = candles[:-1]
        fee_rate = info.taker_fee if params.fee_rate is None else params.fee_rate
        try:
            result = run_backtest(candles, strategy, info.contract_value, fee_rate, params.slippage_bps)
        except NotEnoughCandles as error:
            raise HTTPException(422, detail=str(error)) from error
        run = StoredRun(uuid4(), _params_json(params, fee_rate), _metrics_json(result), result.trades)
        store.save(run.run_id, strategy.name, run.params, run.metrics, run.trades)
        return _run_json(run)

    @router.get("/{run_id}")
    def get_backtest(run_id: UUID) -> dict[str, Any]:
        run = store.get(run_id)
        if run is None:
            raise HTTPException(404, detail="backtest run not found")
        return _run_json(run)

    return router
