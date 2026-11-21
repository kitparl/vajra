"""Postgres 18 with the real init script; the service connects as its own role."""

from collections.abc import Callable, Iterator
from pathlib import Path

import pytest
from testcontainers.community.postgres import PostgresContainer
from vajra_common.settings import DatabaseSettings

SCHEMA = "research"
ROLE = f"{SCHEMA}_svc"
ROLE_PASSWORD = "test-pw"
REPO_ROOT = Path(__file__).resolve().parents[3]


def _postgres_image() -> str:
    """Image tag lives only in infra/compose/.env.example."""
    for line in (REPO_ROOT / "infra/compose/.env.example").read_text().splitlines():
        if line.startswith("POSTGRES_IMAGE="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("POSTGRES_IMAGE missing in infra/compose/.env.example")


def _start_postgres() -> PostgresContainer:
    container = PostgresContainer(_postgres_image(), dbname="vajra", driver="psycopg").with_volume_mapping(
        str(REPO_ROOT / "infra/postgres/init/01-roles-and-schemas.sh"),
        "/docker-entrypoint-initdb.d/01-roles-and-schemas.sh",
    )
    for name in ("CORE", "RISK", "GATEWAY", "RESEARCH", "SIGNAL"):
        container.with_env(f"{name}_DB_PASSWORD", ROLE_PASSWORD)
    return container.start()


def _settings_for(container: PostgresContainer) -> DatabaseSettings:
    host = container.get_container_host_ip()
    port = container.get_exposed_port(5432)
    return DatabaseSettings(db_url=f"postgresql+psycopg://{host}:{port}/vajra", db_user=ROLE, db_password=ROLE_PASSWORD)


@pytest.fixture(scope="session")
def schema() -> str:
    return SCHEMA


@pytest.fixture(scope="session")
def start_postgres() -> Callable[[], PostgresContainer]:
    return _start_postgres


@pytest.fixture(scope="session")
def settings_for() -> Callable[[PostgresContainer], DatabaseSettings]:
    return _settings_for


@pytest.fixture(scope="module")
def postgres() -> Iterator[PostgresContainer]:
    container = _start_postgres()
    yield container
    container.stop()


@pytest.fixture(scope="module")
def settings(postgres: PostgresContainer) -> DatabaseSettings:
    return _settings_for(postgres)
