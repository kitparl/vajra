package com.vajra.coreservice.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.vajra.coreservice.connector.Candle;
import com.vajra.coreservice.connector.ExchangeConnector;
import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.connector.delta.DeltaApiException;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuotePollerTest {

    private static final Instant T0 = Instant.parse("2026-11-21T10:00:00Z");
    private static final Duration MAX_AGE = Duration.ofSeconds(10);

    private final MutableClock clock = new MutableClock(T0);
    private final QuoteCache cache = new QuoteCache(clock);
    private final SwitchableConnector connector = new SwitchableConnector();
    private final QuotePoller poller = new QuotePoller(connector, cache, new MarketDataProperties(Set.of("BTCUSD", "ETHUSD")));

    @Test
    void keepsOnlyEnabledSymbols() {
        connector.quotes = List.of(QuoteCacheTest.quote("BTCUSD", T0), QuoteCacheTest.quote("ETHUSD", T0),
                QuoteCacheTest.quote("SOLUSD", T0));

        poller.poll();

        assertThat(cache.fresh("BTCUSD", MAX_AGE)).isPresent();
        assertThat(cache.fresh("ETHUSD", MAX_AGE)).isPresent();
        assertThat(cache.fresh("SOLUSD", MAX_AGE)).isEmpty();
    }

    @Test
    void pollFailureKeepsLastQuoteAndDoesNotThrow() {
        connector.quotes = List.of(QuoteCacheTest.quote("BTCUSD", T0));
        poller.poll();
        connector.failure = new DeltaApiException("down", false, null);
        clock.advance(Duration.ofSeconds(2));

        assertThatCode(poller::poll).doesNotThrowAnyException();
        assertThat(cache.fresh("BTCUSD", MAX_AGE)).map(Quote::receivedAt).contains(T0);
    }

    private static final class SwitchableConnector implements ExchangeConnector {
        List<Quote> quotes = List.of();
        RuntimeException failure;

        @Override
        public Venue venue() {
            return Venue.DELTA_TESTNET;
        }

        @Override
        public List<Instrument> instruments(Set<String> enabledSymbols) {
            return List.of();
        }

        @Override
        public List<Quote> quotes() {
            if (failure != null) {
                throw failure;
            }
            return quotes;
        }

        @Override
        public List<Candle> candles1m(String symbol, Instant from, Instant to) {
            return List.of();
        }
    }
}
