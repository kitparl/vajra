package com.vajra.coreservice.connector.delta;

import java.math.BigDecimal;
import java.time.Instant;

/** One OHLCV candle from Delta {@code GET /v2/history/candles}; {@code time} is the candle's open time. */
public record DeltaCandle(Instant time, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume) {}
