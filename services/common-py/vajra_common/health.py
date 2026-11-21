import logging

from fastapi import APIRouter
from fastapi.responses import JSONResponse
from sqlalchemy import Engine, text
from sqlalchemy.exc import SQLAlchemyError

logger = logging.getLogger(__name__)


def health_router(engine: Engine) -> APIRouter:
    """GET /health: 200 UP when the database answers, otherwise 503 DOWN."""
    router = APIRouter()

    @router.get("/health")
    def health() -> JSONResponse:
        try:
            with engine.connect() as conn:
                conn.execute(text("SELECT 1"))
        except SQLAlchemyError:
            logger.warning("health check: database unreachable", exc_info=True)
            return JSONResponse(status_code=503, content={"status": "DOWN"})
        return JSONResponse(status_code=200, content={"status": "UP"})

    return router
