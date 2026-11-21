package com.vajra.coreservice.marketdata;

import com.vajra.coreservice.connector.Quote;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Latest quote per symbol, in memory only. Slice 2's paper venue reads it with a 10 s max age. */
@Component
public class QuoteCache {

    private final Map<String, Quote> latest = new ConcurrentHashMap<>();
    private final Clock clock;

    public QuoteCache(Clock clock) {
        this.clock = clock;
    }

    public void put(Quote quote) {
        latest.put(quote.symbol(), quote);
    }

    /** The latest quote, or empty if there is none or it was received more than {@code maxAge} ago. */
    public Optional<Quote> fresh(String symbol, Duration maxAge) {
        return Optional.ofNullable(latest.get(symbol))
                .filter(q -> !Duration.between(q.receivedAt(), clock.instant()).minus(maxAge).isPositive());
    }
}
