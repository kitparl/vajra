package com.vajra.coreservice.marketdata;

import com.vajra.coreservice.connector.Quote;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Latest quote per symbol, in memory only. */
@Component
public class QuoteCache {

    /** A quote received longer ago than this is stale: not used for paper fills or marks, flagged in the API. */
    public static final Duration MAX_AGE = Duration.ofSeconds(10);

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
                .filter(q -> !isStale(q, maxAge));
    }

    /** True if the quote was received more than {@code maxAge} ago. */
    public boolean isStale(Quote quote, Duration maxAge) {
        return Duration.between(quote.receivedAt(), clock.instant()).compareTo(maxAge) > 0;
    }

    /** Every cached quote, in no particular order. */
    public List<Quote> all() {
        return List.copyOf(latest.values());
    }
}
