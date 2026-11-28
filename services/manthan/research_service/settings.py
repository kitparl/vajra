from pydantic import Field
from pydantic_settings import BaseSettings


class ResearchSettings(BaseSettings):
    """Read from env var CORE_BASE_URL (core-service REST base URL, the only source of candles)."""

    core_base_url: str = Field(min_length=1)
