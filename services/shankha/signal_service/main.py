"""signal-service: runs strategies and promoted models, publishes signals."""

from fastapi import FastAPI
from vajra_common.db import create_engine_from
from vajra_common.health import health_router
from vajra_common.settings import DatabaseSettings

SCHEMA = "signal"


def create_app(settings: DatabaseSettings | None = None) -> FastAPI:
    app = FastAPI(title="SignalService")
    app.include_router(health_router(create_engine_from(settings or DatabaseSettings())))
    return app
