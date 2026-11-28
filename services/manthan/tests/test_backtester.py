import time
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest
from research_service.backtester import NotEnoughCandles, run_backtest
from vajra_strategies import Candle, Side, SmaCross

T0 = datetime(2026, 1, 1, tzinfo=UTC)
D = Decimal


def _candle(minute: int, open_: str, close: str) -> Candle:
    o, c = D(open_), D(close)
    return Candle(T0 + timedelta(minutes=minute), o, max(o, c), min(o, c), c, D(1))


def _series(rows: list[tuple[str, str]]) -> list[Candle]:
    return [_candle(i, o, c) for i, (o, c) in enumerate(rows)]


class _Scripted(SmaCross):
    """SmaCross whose decide() follows a minute -> side script (still honours the position rules)."""

    def __init__(self, script: dict[int, Side]) -> None:
        super().__init__(fast=1, slow=2)
        self._script = script

    def decide(self, closed, position):  # type: ignore[no-untyped-def]
        side = self._script.get(int((closed[-1].open_time - T0).total_seconds() // 60))
        if (side == "BUY" and position == 0) or (side == "SELL" and position > 0):
            return side
        return None


# fast=2 slow=3 -> min_candles 4. closes 10,10,10,8,14,14,6,6
# i=4 window 10,10,8,14: prev fast 9 <= slow 9.33, last fast 11 > slow 10.67 -> BUY, fills at candle 5 open.
# i=6 window 8,14,14,6: prev fast 14 >= slow 12, last fast 10 < slow 11.33 -> SELL, fills at candle 7 open.
CROSS_ROWS = [("10", "10"), ("10", "10"), ("10", "10"), ("10", "8"), ("8", "14"), ("15", "14"), ("14", "6"), ("7", "6")]


def test_fill_at_next_open_with_slippage_and_fees() -> None:
    result = run_backtest(_series(CROSS_ROWS), SmaCross(2, 3), D("0.001"), D("0.0005"), D("2"))

    [trade] = result.trades
    # entry 15 * (1 + 2/10000) = 15.003 ; exit 7 * (1 - 2/10000) = 6.9986
    assert trade.entry_price == D("15.003")
    assert trade.exit_price == D("6.9986")
    assert (trade.entry_time, trade.exit_time) == (T0 + timedelta(minutes=5), T0 + timedelta(minutes=7))
    assert trade.side == "BUY"
    assert trade.closed_at_end is False
    # fee entry = 0.0005 * 1 * 0.001 * 15.003 = 0.0000075015 ; exit = 0.0005 * 0.001 * 6.9986 = 0.0000034993
    assert trade.fees == D("0.0000110008")
    # gross = (6.9986 - 15.003) * 0.001 = -0.0080044 ; net = -0.0080044 - 0.0000110008
    assert result.gross_pnl == D("-0.0080044")
    assert trade.pnl == D("-0.0080154008")
    assert result.net_pnl == D("-0.0080154008")
    assert result.fees == D("0.0000110008")
    assert result.win_rate == D(0)
    assert result.funding_included is False


def test_open_position_closed_at_end() -> None:
    rows = CROSS_ROWS[:6]  # BUY fills at candle 5 open 15, never sells; last close 14
    result = run_backtest(_series(rows), SmaCross(2, 3), D(1), D("0.001"), D(0))

    [trade] = result.trades
    assert trade.closed_at_end is True
    assert trade.entry_price == D(15)
    assert trade.exit_price == D(14)
    assert trade.exit_time == T0 + timedelta(minutes=6)  # close time of candle 5
    # fees = 0.001*15 + 0.001*14 = 0.029 ; pnl = (14 - 15) - 0.029
    assert trade.fees == D("0.029")
    assert trade.pnl == D("-1.029")


def test_max_drawdown_and_win_rate() -> None:
    rows = [("10", "10"), ("10", "12"), ("12", "16"), ("16", "13"), ("14", "9"), ("9", "8"), ("8", "8")]
    script: dict[int, Side] = {0: "BUY", 2: "SELL", 3: "BUY", 4: "SELL"}
    result = run_backtest(_series(rows), _Scripted(script), D(1), D(0), D(0))

    assert [t.pnl for t in result.trades] == [D(6), D(-5)]  # 16-10 ; 9-14
    # equity at closes: 0, 2, 6, 6 (flat after exit), 1 (14->9 open), 1, 1 ; peak 6, trough 1
    assert result.max_drawdown == D(5)
    assert result.win_rate == D("0.5")
    assert result.net_pnl == D(1)
    assert result.gross_pnl == D(1)


def test_no_trades_result() -> None:
    rows = [("5", "5")] * 6
    result = run_backtest(_series(rows), SmaCross(2, 3), D("0.001"), D("0.0005"), D(2))

    assert result.trades == []
    assert result.win_rate is None
    for value in (result.gross_pnl, result.fees, result.net_pnl, result.max_drawdown):
        assert value == 0


def test_backtest_decisions_match_live_decide() -> None:
    closes = [100, 101, 99, 98, 97, 103, 108, 107, 99, 95, 94, 102, 110, 111, 100, 90, 91, 105, 120, 119]
    candles = _series([(str(c), str(c)) for c in closes])
    strategy = SmaCross(2, 3)

    result = run_backtest(candles, strategy, D(1), D(0), D(0))

    expected: list[tuple[Side, datetime]] = []
    position = 0
    for i in range(len(candles) - 1):
        side = strategy.decide(candles[: i + 1], position)
        if side:
            expected.append((side, candles[i + 1].open_time))
            position = 1 if side == "BUY" else 0
    assert len(result.trades) >= 2
    actual: list[tuple[Side, datetime]] = []
    for t in result.trades:
        actual.append(("BUY", t.entry_time))
        if not t.closed_at_end:
            actual.append(("SELL", t.exit_time))
    assert actual == expected


def test_missing_minutes_counted() -> None:
    candles = _series([("5", "5")] * 8)
    gapped = candles[:3] + candles[5:]  # minutes 3 and 4 are absent

    result = run_backtest(gapped, SmaCross(2, 3), D(1), D(0), D(0))

    assert result.candle_count == 6
    assert result.missing_minutes == 2  # minutes 0..7 inclusive = 8, minus 6 candles


def test_too_few_candles() -> None:
    with pytest.raises(NotEnoughCandles):
        run_backtest(_series([("5", "5")] * 4), SmaCross(2, 3), D(1), D(0), D(0))


def test_large_series_runs_fast() -> None:
    closes = [D(100 + (i * 7) % 23 - (i // 500) % 5) for i in range(130_000)]
    candles = [Candle(T0 + timedelta(minutes=i), c, c, c, c, D(1)) for i, c in enumerate(closes)]

    started = time.monotonic()
    result = run_backtest(candles, SmaCross(20, 60), D("0.001"), D("0.0005"), D(2))

    assert time.monotonic() - started < 10
    assert result.candle_count == 130_000
    assert result.missing_minutes == 0


def test_backtest_skips_decisions_across_gap() -> None:
    # CROSS_ROWS BUYs on the decision at minute 4; a 10-minute hole inside that tail voids it
    base = _series(list(CROSS_ROWS))
    gapped = [*base[:3], *(replace(c, open_time=c.open_time + timedelta(minutes=10)) for c in base[3:])]

    assert len(run_backtest(base, SmaCross(2, 3), D(1), D(0), D(0)).trades) == 1
    assert run_backtest(gapped, SmaCross(2, 3), D(1), D(0), D(0)).trades == []
