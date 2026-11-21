"""Alembic helpers. Migrations run as a separate pre-start step, never inside the app (see 0016)."""

from pathlib import Path

from alembic import command, context
from alembic.config import Config

from vajra_common.db import create_engine_from
from vajra_common.settings import DatabaseSettings


def run_migrations(alembic_ini: Path, settings: DatabaseSettings) -> None:
    """Upgrade the service's schema to head."""
    config = Config(str(alembic_ini))
    config.attributes["settings"] = settings
    command.upgrade(config, "head")


def run_online(schema: str) -> None:
    """Body of every service's alembic/env.py: migrate inside the service's own schema."""
    settings = context.config.attributes.get("settings") or DatabaseSettings()
    with create_engine_from(settings).connect() as connection:
        context.configure(connection=connection, version_table_schema=schema, target_metadata=None)
        with context.begin_transaction():
            context.run_migrations()
