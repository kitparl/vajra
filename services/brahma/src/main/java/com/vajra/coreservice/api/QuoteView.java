package com.vajra.coreservice.api;

import com.vajra.coreservice.connector.Quote;
import java.math.BigDecimal;
import java.time.Instant;

/** A cached quote as the API shows it; {@code stale} means older than {@code QuoteCache.MAX_AGE}. */
record QuoteView(String symbol, BigDecimal bestBid, BigDecimal bestAsk, BigDecimal markPrice, Instant venueTime,
        Instant receivedAt, boolean stale) {

    static QuoteView of(Quote q, boolean stale) {
        return new QuoteView(q.symbol(), q.bestBid(), q.bestAsk(), q.markPrice(), q.venueTime(), q.receivedAt(), stale);
    }
}
