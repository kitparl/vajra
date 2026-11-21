from pydantic import SecretStr
from pydantic_settings import BaseSettings


class DatabaseSettings(BaseSettings):
    """Read from env vars DB_URL, DB_USER, DB_PASSWORD. All required: missing ones fail startup by name."""

    db_url: str  # e.g. postgresql+psycopg://postgres:5432/vajra (no credentials)
    db_user: str
    db_password: SecretStr
