package com.vajra.brahma.connector;

import java.math.BigDecimal;
import java.time.Instant;

/** Latest top of book for one symbol. {@code receivedAt} is our clock, used for staleness checks. */
public record Quote(String symbol, BigDecimal bestBid, BigDecimal bestAsk, BigDecimal markPrice, Instant venueTime, Instant receivedAt) {}
