"""Pure backtester: replays closed candles through a strategy with next-open fills, slippage and fees."""

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal

from vajra_strategies import Candle, Side, SmaCross

_BPS = Decimal(10_000)
_MINUTE = timedelta(minutes=1)


class NotEnoughCandles(ValueError):
    """The series is too short for the strategy to decide even once and fill."""


@dataclass(frozen=True)
class BacktestParams:
    symbol: str
    start: datetime
    end: datetime
    fast: int = 20
    slow: int = 60
    slippage_bps: Decimal = Decimal("2")
    fee_rate: Decimal | None = None  # None: use the instrument's taker fee


@dataclass(frozen=True)
class Trade:
    side: Side  # entry side; the strategy is long-only, so always BUY
    entry_time: datetime
    entry_price: Decimal
    exit_time: datetime
    exit_price: Decimal
    fees: Decimal
    pnl: Decimal  # net of both fees
    closed_at_end: bool


@dataclass(frozen=True)
class BacktestResult:
    trades: list[Trade]
    candle_count: int
    missing_minutes: int
    gross_pnl: Decimal
    fees: Decimal
    net_pnl: Decimal
    max_drawdown: Decimal
    win_rate: Decimal | None
    funding_included: bool = False


@dataclass(frozen=True)
class _Entry:
    time: datetime
    price: Decimal
    fee: Decimal


def run_backtest(
    candles: Sequence[Candle],
    strategy: SmaCross,
    contract_value: Decimal,
    fee_rate: Decimal,
    slippage_bps: Decimal,
    size: int = 1,
) -> BacktestResult:
    """Decide on each closed candle, fill at the next candle's open. Long-only, one position at a time."""
    if len(candles) < strategy.min_candles + 1:
        raise NotEnoughCandles(f"not enough candles: need {strategy.min_candles + 1}, got {len(candles)}")

    notional = size * contract_value
    up, down = 1 + slippage_bps / _BPS, 1 - slippage_bps / _BPS
    trades: list[Trade] = []
    entry: _Entry | None = None
    realised = Decimal(0)  # net of all fees paid so far (entry fee of an open position included)
    peak = Decimal(0)
    max_drawdown = Decimal(0)

    def fee_for(price: Decimal) -> Decimal:
        return fee_rate * notional * price

    def close_trade(at: datetime, price: Decimal, closed_at_end: bool) -> None:
        nonlocal entry, realised
        assert entry is not None
        exit_fee = fee_for(price)
        gross = (price - entry.price) * notional
        trades.append(
            Trade(
                "BUY",
                entry.time,
                entry.price,
                at,
                price,
                entry.fee + exit_fee,
                gross - entry.fee - exit_fee,
                closed_at_end,
            )
        )
        realised += gross - exit_fee
        entry = None

    def track(close: Decimal) -> None:
        nonlocal peak, max_drawdown
        equity = realised + ((close - entry.price) * notional if entry else 0)
        peak = max(peak, equity)
        max_drawdown = max(max_drawdown, peak - equity)

    last = len(candles) - 1
    for i in range(last):
        track(candles[i].close)
        # the strategy only reads its last `slow + 1` candles, so pass just that tail (O(1) per step, not O(n))
        side = strategy.decide(candles[max(0, i - strategy.slow) : i + 1], 1 if entry else 0)
        nxt = candles[i + 1]
        if side == "BUY" and entry is None:
            price = nxt.open * up
            entry = _Entry(nxt.open_time, price, fee_for(price))
            realised -= entry.fee
        elif side == "SELL" and entry is not None:
            close_trade(nxt.open_time, nxt.open * down, False)
    track(candles[last].close)
    if entry is not None:
        close_trade(candles[last].open_time + _MINUTE, candles[last].close * down, True)  # that candle's close time
        track(candles[last].close)

    net = sum((t.pnl for t in trades), Decimal(0))
    fees = sum((t.fees for t in trades), Decimal(0))
    wins = sum(1 for t in trades if t.pnl > 0)
    span = (candles[last].open_time - candles[0].open_time) // _MINUTE + 1
    return BacktestResult(
        trades=trades,
        candle_count=len(candles),
        missing_minutes=span - len(candles),
        gross_pnl=net + fees,
        fees=fees,
        net_pnl=net,
        max_drawdown=max_drawdown,
        win_rate=Decimal(wins) / Decimal(len(trades)) if trades else None,
    )
