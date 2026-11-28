import logging
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from typing import Protocol

from signal_service.signal_log import SignalLog, SignalRow

MAX_SIGNAL_AGE = timedelta(minutes=4)
UNPUBLISHED_BATCH = 100
Clock = Callable[[], datetime]
log = logging.getLogger(__name__)


def _utc_now() -> datetime:
    return datetime.now(UTC)


class Publisher(Protocol):
    async def publish(self, row: SignalRow) -> None: ...


async def publish_row(signal_log: SignalLog, publisher: Publisher, row: SignalRow) -> None:
    """Publish one stored row; on failure keep it pending for the next tick."""
    try:
        await publisher.publish(row)
    except Exception:
        log.exception("publish failed, will retry next tick: signal_id=%s", row.signal_id)
        return
    signal_log.mark_published(row.signal_id, datetime.now(UTC))


async def publish_if_fresh(
    signal_log: SignalLog, publisher: Publisher, row: SignalRow, clock: Clock = _utc_now
) -> None:
    """Expire the row if older than MAX_SIGNAL_AGE at this very moment, else publish it.

    The clock is read right before the send, because earlier sends in the same tick may have blocked.
    """
    now = clock()
    age = now - (row.candle_time if row.candle_time is not None else row.generated_at)
    if age > MAX_SIGNAL_AGE:
        log.warning(
            "stale outbox signal expired, not published: signal_id=%s strategy=%s side=%s age=%s",
            row.signal_id,
            row.strategy,
            row.side,
            age,
        )
        signal_log.mark_expired(row.signal_id, now)
        return
    await publish_row(signal_log, publisher, row)


async def republish_pending(signal_log: SignalLog, publisher: Publisher, clock: Clock = _utc_now) -> None:
    """Republish pending outbox rows; rows older than MAX_SIGNAL_AGE are expired, never published."""
    for row in signal_log.unpublished(UNPUBLISHED_BATCH):
        await publish_if_fresh(signal_log, publisher, row, clock)
