package com.vajra.brahma.connector;

import java.math.BigDecimal;
import java.time.Instant;

/** One 1-minute OHLCV candle; {@code openTime} is the start of the minute (UTC). */
public record Candle(String symbol, Instant openTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume) {}
