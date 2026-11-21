package com.vajra.coreservice.connector.delta;

import java.math.BigDecimal;
import java.time.Instant;

/** Top of book and mark price for one symbol, from Delta {@code GET /v2/tickers}. */
public record DeltaTicker(String symbol, BigDecimal bestBid, BigDecimal bestAsk, BigDecimal markPrice, Instant timestamp) {}
