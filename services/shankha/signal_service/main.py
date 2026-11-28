"""signal-service: runs strategies and promoted models, publishes signals."""

import asyncio
import contextlib
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from vajra_common.core_client import CoreClient
from vajra_common.db import create_engine_from
from vajra_common.health import health_router
from vajra_common.settings import DatabaseSettings
from vajra_strategies import SmaCross

from signal_service.heartbeat import Heartbeat
from signal_service.publisher import SignalPublisher
from signal_service.settings import HeartbeatSettings, StrategySettings
from signal_service.signal_log import SignalLog
from signal_service.sma_cross_runner import SmaCrossRunner

SCHEMA = "signal"


def create_app(
    settings: DatabaseSettings | None = None,
    heartbeat_settings: HeartbeatSettings | None = None,
    strategy_settings: StrategySettings | None = None,
) -> FastAPI:
    heartbeat_settings = heartbeat_settings or HeartbeatSettings()
    strategy_settings = strategy_settings or StrategySettings()
    engine = create_engine_from(settings or DatabaseSettings())

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        if strategy_settings.strategy == "none":
            yield
            return
        publisher = SignalPublisher(heartbeat_settings.kafka_bootstrap)
        core: CoreClient | None = None
        if strategy_settings.strategy == "heartbeat":
            runner = Heartbeat(SignalLog(engine), publisher, heartbeat_settings).run()
        else:
            core = CoreClient(str(strategy_settings.core_base_url))
            strategy = SmaCross(strategy_settings.sma_fast, strategy_settings.sma_slow)
            sma_runner = SmaCrossRunner(
                SignalLog(engine), publisher, core, strategy, strategy_settings, heartbeat_settings.trading_mode
            )
            runner = sma_runner.run()
        task = asyncio.create_task(runner)
        try:
            yield
        finally:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
            await publisher.stop()
            if core is not None:
                core.close()

    app = FastAPI(title="SignalService", lifespan=lifespan)
    app.include_router(health_router(engine))
    return app
