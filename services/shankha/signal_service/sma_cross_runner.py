import asyncio
import logging
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from uuid import uuid4

from sqlalchemy.exc import IntegrityError
from vajra_common.core_client import CoreClient, CoreUnavailable
from vajra_strategies import SmaCross

from signal_service.outbox import MAX_SIGNAL_AGE, Publisher, publish_if_fresh, republish_pending
from signal_service.settings import StrategySettings
from signal_service.signal_log import SignalLog, SignalRow

CANDLE_LENGTH = timedelta(minutes=1)
EXTRA_CANDLES = 5 + 3  # decision span slack plus the dropped snapshot and sync lag
CANDLE_INDEX = "signal_log_candle_uidx"
log = logging.getLogger(__name__)


class SmaCrossRunner:
    """Runs SmaCross on final 1m candles (never the newest stored one); one signal per candle."""

    def __init__(
        self,
        signal_log: SignalLog,
        publisher: Publisher,
        core: CoreClient,
        strategy: SmaCross,
        settings: StrategySettings,
        trading_mode: str,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self._log = signal_log
        self._publisher = publisher
        self._core = core
        self._strategy = strategy
        self._settings = settings
        self._venue = trading_mode
        self._clock = clock

    async def tick(self) -> None:
        now = self._clock()
        await republish_pending(self._log, self._publisher, self._clock)
        symbol = self._settings.sma_symbol
        start = now - (self._strategy.slow + EXTRA_CANDLES) * CANDLE_LENGTH
        try:
            candles = await asyncio.to_thread(self._core.candles, symbol, start, now)
            position = await asyncio.to_thread(self._core.position_size, self._venue, symbol)
        except CoreUnavailable as error:
            log.warning("core-service unavailable, skipping tick: %s", error)
            return
        # core re-fetches only the newest stored candle, so it may be a snapshot even after its minute closed
        closed = [c for c in candles[:-1] if c.open_time + CANDLE_LENGTH <= now]
        if not closed:
            return
        last = closed[-1]
        if last.open_time < now - MAX_SIGNAL_AGE:
            log.warning("stale candles, newest final candle opened at %s", last.open_time)
            return
        side = self._strategy.decide(closed, position)
        if side is None or self._log.has_signal_for_candle(self._strategy.name, symbol, last.open_time):
            return
        row = SignalRow(
            signal_id=uuid4(),
            strategy=self._strategy.name,
            symbol=symbol,
            side=side,
            order_type="MARKET",
            size=self._settings.sma_size,
            generated_at=now,
            candle_time=last.open_time,
        )
        try:
            self._log.insert(row)
        except IntegrityError as error:
            if getattr(getattr(error.orig, "diag", None), "constraint_name", None) != CANDLE_INDEX:
                raise
            log.info("signal for candle %s already exists, not publishing again", last.open_time)
            return
        await publish_if_fresh(self._log, self._publisher, row, self._clock)

    async def run(self) -> None:
        """First tick one interval after start, so restarts do not burst. Stops on cancel."""
        while True:
            await asyncio.sleep(self._settings.sma_interval_seconds)
            try:
                await self.tick()
            except Exception:
                log.exception("sma_cross tick failed")
