import asyncio
import socket
from collections.abc import Iterator
from pathlib import Path

import pytest
from aiokafka import AIOKafkaConsumer
from aiokafka.admin import AIOKafkaAdminClient, NewTopic
from google.protobuf.any_pb2 import Any
from signal_service.heartbeat import Heartbeat
from signal_service.publisher import TOPIC, SignalPublisher
from signal_service.settings import HeartbeatSettings
from signal_service.signal_log import SignalLog
from sqlalchemy import text
from testcontainers.core.container import DockerContainer
from testcontainers.core.wait_strategies import LogMessageWaitStrategy
from vajra.common.v1.event_envelope_pb2 import EventEnvelope
from vajra.events.v1.signal_generated_pb2 import SignalGenerated
from vajra_common.db import create_engine_from
from vajra_common.migrations import run_migrations
from vajra_common.settings import DatabaseSettings

SERVICE_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]


def _kafka_image() -> str:
    """Image tag lives only in infra/compose/.env.example."""
    for line in (REPO_ROOT / "infra/compose/.env.example").read_text().splitlines():
        if line.startswith("KAFKA_IMAGE="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("KAFKA_IMAGE missing in infra/compose/.env.example")


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("", 0))
        return sock.getsockname()[1]


@pytest.fixture(scope="module")
def kafka_bootstrap() -> Iterator[str]:
    """Single-node KRaft apache/kafka. testcontainers' KafkaContainer targets the confluent image, not this one.

    The container port equals the host port, so the advertised listener is reachable from the test.
    """
    port = _free_port()
    container = (
        DockerContainer(_kafka_image())
        .with_bind_ports(port, port)
        .with_env("KAFKA_NODE_ID", "1")
        .with_env("KAFKA_PROCESS_ROLES", "broker,controller")
        .with_env("KAFKA_LISTENERS", f"PLAINTEXT://:{port},CONTROLLER://:9093")
        .with_env("KAFKA_ADVERTISED_LISTENERS", f"PLAINTEXT://localhost:{port}")
        .with_env("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT")
        .with_env("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
        .with_env("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
        .with_env("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
        .with_env("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
        .with_env("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
        .with_env("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
        .waiting_for(LogMessageWaitStrategy("Kafka Server started").with_startup_timeout(90))
    )
    with container:
        yield f"localhost:{port}"


async def _tick_and_consume(bootstrap: str, log: SignalLog) -> tuple[bytes | None, bytes]:
    admin = AIOKafkaAdminClient(bootstrap_servers=bootstrap)
    await admin.start()
    await admin.create_topics([NewTopic(TOPIC, num_partitions=1, replication_factor=1)])
    await admin.close()

    consumer = AIOKafkaConsumer(TOPIC, bootstrap_servers=bootstrap, auto_offset_reset="earliest")
    await consumer.start()
    publisher = SignalPublisher(bootstrap)
    try:
        await Heartbeat(log, publisher, HeartbeatSettings(kafka_bootstrap=bootstrap)).tick()
        message = await asyncio.wait_for(consumer.getone(), timeout=30)
        return message.key, message.value
    finally:
        await publisher.stop()
        await consumer.stop()


def test_tick_publishes_envelope_to_signal_generated(kafka_bootstrap: str, settings: DatabaseSettings) -> None:
    run_migrations(SERVICE_ROOT / "alembic.ini", settings)
    engine = create_engine_from(settings)
    with engine.begin() as conn:
        conn.execute(text("delete from signal.signal_log"))
    log = SignalLog(engine)

    key, value = asyncio.run(_tick_and_consume(kafka_bootstrap, log))

    envelope = EventEnvelope.FromString(value)
    event = SignalGenerated()
    assert isinstance(envelope.payload, Any)
    assert envelope.payload.Unpack(event)
    with engine.connect() as conn:
        signal_id, published = conn.execute(
            text("select signal_id, published_at is not null from signal.signal_log")
        ).one()
    assert key == b"BTCUSD"
    assert envelope.event_type == "signal.generated"
    assert envelope.producer == "signal-service"
    assert event.signal_id == str(signal_id)
    assert published
    engine.dispose()


def test_tick_logs_signal_while_kafka_unreachable_then_publishes_later(
    kafka_bootstrap: str, settings: DatabaseSettings
) -> None:
    run_migrations(SERVICE_ROOT / "alembic.ini", settings)
    engine = create_engine_from(settings)
    with engine.begin() as conn:
        conn.execute(text("delete from signal.signal_log"))
    log = SignalLog(engine)
    publisher = SignalPublisher("localhost:1")
    heartbeat = Heartbeat(log, publisher, HeartbeatSettings(kafka_bootstrap="localhost:1"))

    async def run() -> None:
        await heartbeat.tick()
        assert len(log.unpublished(10)) == 1
        publisher.bootstrap = kafka_bootstrap  # Kafka "comes back"
        await heartbeat.tick()
        await publisher.stop()

    asyncio.run(run())

    with engine.connect() as conn:
        published = [r[0] for r in conn.execute(text("select published_at is not null from signal.signal_log"))]
    assert published == [True, True]
    engine.dispose()
