"""signal-service: runs strategies and promoted models, publishes signals."""

import asyncio
import contextlib
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from vajra_common.db import create_engine_from
from vajra_common.health import health_router
from vajra_common.settings import DatabaseSettings

from signal_service.heartbeat import Heartbeat
from signal_service.publisher import SignalPublisher
from signal_service.settings import HeartbeatSettings
from signal_service.signal_log import SignalLog

SCHEMA = "signal"


def create_app(
    settings: DatabaseSettings | None = None, heartbeat_settings: HeartbeatSettings | None = None
) -> FastAPI:
    heartbeat_settings = heartbeat_settings or HeartbeatSettings()
    engine = create_engine_from(settings or DatabaseSettings())

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        if not heartbeat_settings.heartbeat_enabled:
            yield
            return
        publisher = SignalPublisher(heartbeat_settings.kafka_bootstrap)
        task = asyncio.create_task(Heartbeat(SignalLog(engine), publisher, heartbeat_settings).run())
        try:
            yield
        finally:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
            await publisher.stop()

    app = FastAPI(title="SignalService", lifespan=lifespan)
    app.include_router(health_router(engine))
    return app
