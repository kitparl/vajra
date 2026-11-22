import asyncio
import logging
from datetime import UTC, datetime
from typing import Protocol
from uuid import uuid4

from signal_service.settings import HeartbeatSettings
from signal_service.signal_log import SignalLog, SignalRow

STRATEGY = "heartbeat"
UNPUBLISHED_BATCH = 100
log = logging.getLogger(__name__)


class Publisher(Protocol):
    async def publish(self, row: SignalRow) -> None: ...


def next_side(last: str | None) -> str:
    return "SELL" if last == "BUY" else "BUY"


class Heartbeat:
    """Alternating BUY/SELL test signal, proves the signal path end to end. Not a trading strategy."""

    def __init__(self, signal_log: SignalLog, publisher: Publisher, settings: HeartbeatSettings) -> None:
        self._log = signal_log
        self._publisher = publisher
        self._settings = settings

    async def tick(self) -> None:
        for row in self._log.unpublished(UNPUBLISHED_BATCH):
            await self._publish(row)
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
        await self._publish(row)

    async def _publish(self, row: SignalRow) -> None:
        try:
            await self._publisher.publish(row)
        except Exception:
            log.exception("publish failed, will retry next tick: signal_id=%s", row.signal_id)
            return
        self._log.mark_published(row.signal_id, datetime.now(UTC))

    async def run(self) -> None:
        """First tick one interval after start, so restarts do not burst. Stops on cancel."""
        while True:
            await asyncio.sleep(self._settings.heartbeat_interval_seconds)
            try:
                await self.tick()
            except Exception:
                log.exception("heartbeat tick failed")
