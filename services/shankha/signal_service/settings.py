from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings


class HeartbeatSettings(BaseSettings):
    """Read from env vars TRADING_MODE, KAFKA_BOOTSTRAP, HEARTBEAT_*. LIVE is not a valid mode."""

    trading_mode: Literal["PAPER", "DELTA_TESTNET"] = "PAPER"
    kafka_bootstrap: str = Field(min_length=1)
    heartbeat_interval_seconds: int = Field(default=900, ge=10)
    heartbeat_symbol: str = "BTCUSD"
    heartbeat_size: int = Field(default=1, ge=1)
    heartbeat_enabled: bool = True
