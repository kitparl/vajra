from collections.abc import Callable
from pathlib import Path

from fastapi.testclient import TestClient
from signal_service.main import create_app
from sqlalchemy import text
from testcontainers.community.postgres import PostgresContainer
from vajra_common.db import create_engine_from
from vajra_common.migrations import run_migrations
from vajra_common.settings import DatabaseSettings

SERVICE_ROOT = Path(__file__).resolve().parents[1]


def test_health_up(settings: DatabaseSettings) -> None:
    with TestClient(create_app(settings)) as client:
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_alembic_version_in_own_schema(postgres: PostgresContainer, settings: DatabaseSettings, schema: str) -> None:
    run_migrations(SERVICE_ROOT / "alembic.ini", settings)

    sql = text(
        "select count(*) from information_schema.tables where table_schema = :s and table_name = 'alembic_version'"
    )
    with create_engine_from(settings).connect() as conn:
        own = conn.execute(sql, {"s": schema}).scalar()
        public = conn.execute(sql, {"s": "public"}).scalar()

    assert own == 1
    assert public == 0


def test_health_down_when_db_stops(
    start_postgres: Callable[[], PostgresContainer],
    settings_for: Callable[[PostgresContainer], DatabaseSettings],
) -> None:
    container = start_postgres()  # own container, because this test stops it
    app = create_app(settings_for(container))
    container.stop()

    with TestClient(app) as client:
        response = client.get("/health")

    assert response.status_code == 503
    assert response.json() == {"status": "DOWN"}
