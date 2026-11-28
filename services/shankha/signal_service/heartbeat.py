import asyncio
import logging
from datetime import UTC, datetime
from uuid import uuid4

from signal_service.outbox import Publisher, publish_if_fresh, republish_pending
from signal_service.settings import HeartbeatSettings
from signal_service.signal_log import SignalLog, SignalRow

STRATEGY = "heartbeat"
log = logging.getLogger(__name__)


def next_side(last: str | None) -> str:
    return "SELL" if last == "BUY" else "BUY"


class Heartbeat:
    """Alternating BUY/SELL test signal, proves the signal path end to end. Not a trading strategy."""

    def __init__(self, signal_log: SignalLog, publisher: Publisher, settings: HeartbeatSettings) -> None:
        self._log = signal_log
        self._publisher = publisher
        self._settings = settings

    async def tick(self) -> None:
        await republish_pending(self._log, self._publisher)
        row = SignalRow(
            signal_id=uuid4(),
            strategy=STRATEGY,
            symbol=self._settings.heartbeat_symbol,
            side=next_side(self._log.last_side(STRATEGY)),
            order_type="MARKET",
            size=self._settings.heartbeat_size,
            generated_at=datetime.now(UTC),
        )
        self._log.insert(row)
        await publish_if_fresh(self._log, self._publisher, row)

    async def run(self) -> None:
        """First tick one interval after start, so restarts do not burst. Stops on cancel."""
        while True:
            await asyncio.sleep(self._settings.heartbeat_interval_seconds)
            try:
                await self.tick()
            except Exception:
                log.exception("heartbeat tick failed")
