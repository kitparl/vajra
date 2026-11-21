from sqlalchemy import Engine, create_engine
from sqlalchemy.engine import make_url

from vajra_common.settings import DatabaseSettings

POOL_SIZE = 5
CONNECT_TIMEOUT_SECONDS = 3


def create_engine_from(settings: DatabaseSettings) -> Engine:
    url = make_url(settings.db_url).set(username=settings.db_user, password=settings.db_password.get_secret_value())
    return create_engine(
        url,
        pool_size=POOL_SIZE,
        max_overflow=0,
        pool_pre_ping=True,
        connect_args={"connect_timeout": CONNECT_TIMEOUT_SECONDS},
    )
