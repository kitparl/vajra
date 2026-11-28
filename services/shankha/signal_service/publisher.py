from uuid import uuid4

from aiokafka import AIOKafkaProducer
from google.protobuf.any_pb2 import Any
from vajra.common.v1.event_envelope_pb2 import EventEnvelope
from vajra.events.v1.signal_generated_pb2 import SignalGenerated

from signal_service.signal_log import SignalRow

TOPIC = "signal.generated"
PRODUCER = "signal-service"
REQUEST_TIMEOUT_MS = 10_000  # a blocked send must not eat the 4 min signal age budget (aiokafka has no max_block_ms)


class SignalPublisher:
    """Sends SignalGenerated inside an EventEnvelope. Consumers de-duplicate by signal_id, not event_id."""

    def __init__(self, bootstrap: str) -> None:
        self.bootstrap = bootstrap
        self._producer: AIOKafkaProducer | None = None

    async def _connected_producer(self) -> AIOKafkaProducer:
        """Connect lazily so the service starts with Kafka down; a failed connect is retried on the next publish."""
        if self._producer is None:
            producer = AIOKafkaProducer(
                bootstrap_servers=self.bootstrap, acks="all", request_timeout_ms=REQUEST_TIMEOUT_MS
            )
            try:
                await producer.start()
            except BaseException:
                await producer.stop()  # do not leak a half-started producer
                raise
            self._producer = producer
        return self._producer

    async def stop(self) -> None:
        if self._producer is not None:
            await self._producer.stop()
            self._producer = None

    async def publish(self, row: SignalRow) -> None:
        event = SignalGenerated(
            signal_id=str(row.signal_id),
            strategy=row.strategy,
            symbol=row.symbol,
            side=row.side,
            order_type=row.order_type,
            size=row.size,
        )
        event.generated_at.FromDatetime(row.generated_at)
        payload = Any()
        payload.Pack(event)
        envelope = EventEnvelope(event_id=str(uuid4()), event_type=TOPIC, producer=PRODUCER, payload=payload)
        producer = await self._connected_producer()
        await producer.send_and_wait(TOPIC, envelope.SerializeToString(), key=row.symbol.encode())
