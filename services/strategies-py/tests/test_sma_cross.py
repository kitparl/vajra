import random
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest
from vajra_strategies import Candle, SmaCross


def candle(index: int, close: Decimal) -> Candle:
    return Candle(
        open_time=datetime(2026, 1, 1, tzinfo=UTC) + timedelta(minutes=index),
        open=close,
        high=close,
        low=close,
        close=close,
        volume=Decimal(1),
    )


def series(closes: list[int | str | Decimal]) -> list[Candle]:
    return [candle(i, Decimal(c)) for i, c in enumerate(closes)]


@pytest.fixture
def strategy() -> SmaCross:
    return SmaCross(fast=2, slow=3)


CROSS_UP = [5, 5, 5, 4, 7]  # prev fast 4.5 <= slow 4.67; last fast 5.5 > slow 5.33
CROSS_DOWN = [5, 5, 5, 6, 3]  # prev fast 5.5 > slow 5.33; last fast 4.5 < slow 4.67


def test_cross_up_flat_buys(strategy: SmaCross) -> None:
    assert strategy.decide(series(CROSS_UP), 0) == "BUY"


def test_cross_up_when_long_does_nothing(strategy: SmaCross) -> None:
    assert strategy.decide(series(CROSS_UP), 1) is None


def test_cross_down_long_sells(strategy: SmaCross) -> None:
    assert strategy.decide(series(CROSS_DOWN), 1) == "SELL"


def test_cross_down_flat_does_nothing(strategy: SmaCross) -> None:
    assert strategy.decide(series(CROSS_DOWN), 0) is None


def test_short_position_never_buys(strategy: SmaCross) -> None:
    assert strategy.decide(series(CROSS_UP), -1) is None


def test_too_few_candles_returns_none(strategy: SmaCross) -> None:
    assert strategy.min_candles == 4
    assert strategy.decide(series([5, 4, 7]), 0) is None


def test_only_last_candle_cross_counts(strategy: SmaCross) -> None:
    # cross up at index -2 (prev fast 4.5 <= slow 4.67, then 5.5 > 5.33); last candle keeps fast > slow
    assert strategy.decide(series([*CROSS_UP, 8]), 0) is None


def test_decision_uses_only_last_slow_plus_one_candles(strategy: SmaCross) -> None:
    rng = random.Random(42)
    closes = [Decimal(100)]
    for _ in range(199):
        closes.append(closes[-1] + Decimal(rng.randint(-5, 5)))
    full = series(closes)
    wild = series([1 if i % 2 == 0 else 10**6 for i in range(50)])
    for i in range(strategy.slow, len(full)):
        tail = full[i - strategy.slow : i + 1]
        for position in (0, 1):
            expected = strategy.decide(tail, position)
            assert strategy.decide(full[: i + 1], position) == expected
            assert strategy.decide([*wild, *tail], position) == expected


@pytest.mark.parametrize(("fast", "slow"), [(3, 3), (0, 5)])
def test_invalid_params(fast: int, slow: int) -> None:
    with pytest.raises(ValueError):
        SmaCross(fast, slow)


def test_gap_wider_than_max_span_returns_none(strategy: SmaCross) -> None:
    assert strategy.max_span == timedelta(minutes=8)
    candles = series(CROSS_UP)
    gapped = [*candles[:3], *(replace(c, open_time=c.open_time + timedelta(minutes=10)) for c in candles[3:])]

    assert strategy.decide(gapped, 0) is None


def test_small_gap_within_span_still_decides(strategy: SmaCross) -> None:
    candles = series(CROSS_UP)
    gapped = [*candles[:3], *(replace(c, open_time=c.open_time + timedelta(minutes=1)) for c in candles[3:])]

    assert strategy.decide(gapped, 0) == "BUY"


def test_position_above_one_never_buys(strategy: SmaCross) -> None:
    assert strategy.decide(series(CROSS_UP), 2) is None
