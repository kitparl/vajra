"""research-service: features, backtests, training, model registry."""

from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from datetime import datetime

from fastapi import FastAPI
from vajra_common.core_client import CoreClient
from vajra_common.db import create_engine_from
from vajra_common.health import health_router
from vajra_common.settings import DatabaseSettings

from research_service.backtest_api import backtest_router, utc_now
from research_service.backtest_store import BacktestStore
from research_service.settings import ResearchSettings

SCHEMA = "research"


def create_app(
    settings: DatabaseSettings | None = None,
    research_settings: ResearchSettings | None = None,
    core: CoreClient | None = None,
    clock: Callable[[], datetime] | None = None,
) -> FastAPI:
    engine = create_engine_from(settings or DatabaseSettings())
    owned_core = core is None
    core = core or CoreClient((research_settings or ResearchSettings()).core_base_url)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        yield
        if owned_core:
            core.close()

    app = FastAPI(title="ResearchService", lifespan=lifespan)
    app.include_router(health_router(engine))
    app.include_router(backtest_router(core, BacktestStore(engine), clock or utc_now))
    return app
