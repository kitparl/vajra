from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings


class HeartbeatSettings(BaseSettings):
    """Read from env vars TRADING_MODE, KAFKA_BOOTSTRAP, HEARTBEAT_*. LIVE is not a valid mode."""

    trading_mode: Literal["PAPER", "DELTA_TESTNET"] = "PAPER"
    kafka_bootstrap: str = Field(min_length=1)
    heartbeat_interval_seconds: int = Field(default=900, ge=10)
    heartbeat_symbol: str = "BTCUSD"
    heartbeat_size: int = Field(default=1, ge=1)


class StrategySettings(BaseSettings):
    """Read from env vars STRATEGY, SMA_*, CORE_BASE_URL. STRATEGY is the only on/off switch."""

    strategy: Literal["sma_cross", "heartbeat", "none"] = "sma_cross"
    sma_fast: int = 20
    sma_slow: int = 60
    sma_symbol: str = "BTCUSD"
    sma_size: int = Field(default=1, ge=1)
    sma_interval_seconds: int = Field(default=60, ge=10)
    core_base_url: str | None = None

    @model_validator(mode="after")
    def _core_url_for_sma_cross(self) -> StrategySettings:
        if self.strategy == "sma_cross" and not self.core_base_url:
            raise ValueError("CORE_BASE_URL is required when STRATEGY is sma_cross")
        return self
