package com.vajra.coreservice.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class QuoteCacheTest {

    private static final Instant T0 = Instant.parse("2026-11-21T10:00:00Z");
    private static final Duration MAX_AGE = Duration.ofSeconds(10);

    private final MutableClock clock = new MutableClock(T0);
    private final QuoteCache cache = new QuoteCache(clock);

    @Test
    void freshWithinMaxAge() {
        cache.put(quote("BTCUSD", T0));
        clock.advance(Duration.ofSeconds(9));

        assertThat(cache.fresh("BTCUSD", MAX_AGE)).isPresent();
    }

    @Test
    void staleAfterMaxAge() {
        cache.put(quote("BTCUSD", T0));
        clock.advance(Duration.ofSeconds(11));

        assertThat(cache.fresh("BTCUSD", MAX_AGE)).isEmpty();
    }

    @Test
    void unknownSymbolIsEmpty() {
        assertThat(cache.fresh("ETHUSD", MAX_AGE)).isEmpty();
    }

    static Quote quote(String symbol, Instant receivedAt) {
        return new Quote(symbol, new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("100.5"), receivedAt, receivedAt);
    }
}
