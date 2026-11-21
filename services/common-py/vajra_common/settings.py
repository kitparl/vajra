from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings


class DatabaseSettings(BaseSettings):
    """Read from env vars DB_URL, DB_USER, DB_PASSWORD.

    All required and non-empty: compose passes "" for an unset variable, so empty must fail startup by name too.
    """

    db_url: str = Field(min_length=1)  # e.g. postgresql+psycopg://postgres:5432/vajra (no credentials)
    db_user: str = Field(min_length=1)
    db_password: SecretStr = Field(min_length=1)
