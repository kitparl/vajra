from collections.abc import Sequence
from datetime import timedelta
from decimal import Decimal

from vajra_strategies.candle import Candle, Side


def _sma(closed: Sequence[Candle], period: int) -> Decimal:
    window = closed[-period:]
    return sum((c.close for c in window), Decimal(0)) / period


class SmaCross:
    """Long-only SMA crossover: BUY on a fast-over-slow cross up when flat, SELL on a cross down when long."""

    name = "sma_cross"

    def __init__(self, fast: int = 20, slow: int = 60) -> None:
        if not 1 <= fast < slow:
            raise ValueError(f"require 1 <= fast < slow, got fast={fast} slow={slow}")
        self.fast = fast
        self.slow = slow

    @property
    def min_candles(self) -> int:
        return self.slow + 1

    @property
    def max_span(self) -> timedelta:
        """Widest allowed open_time distance between the first and last candle of the decision window."""
        return timedelta(minutes=self.slow + 5)

    def decide(self, closed: Sequence[Candle], position: int) -> Side | None:
        """Decide on the newest candle.

        `closed` must be ascending, closed-only candles; only the last `slow + 1` are read.
        position 0 (flat) may BUY, position > 0 (long) may SELL, position < 0 never trades.
        Returns None when the window spans more than `max_span` (missing minutes), so live and backtest agree.
        """
        if len(closed) < self.min_candles:
            return None
        window = closed[-self.min_candles :]
        if window[-1].open_time - window[0].open_time > self.max_span:
            return None
        prev_fast, prev_slow = _sma(window[:-1], self.fast), _sma(window[:-1], self.slow)
        last_fast, last_slow = _sma(window, self.fast), _sma(window, self.slow)
        if position == 0 and prev_fast <= prev_slow and last_fast > last_slow:
            return "BUY"
        if position > 0 and prev_fast >= prev_slow and last_fast < last_slow:
            return "SELL"
        return None
