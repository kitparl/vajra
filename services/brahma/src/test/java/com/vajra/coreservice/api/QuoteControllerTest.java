package com.vajra.coreservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.marketdata.QuoteCache;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final QuoteCache cache = new QuoteCache(clock);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final QuoteController controller = new QuoteController(cache, instruments);

    @Test
    void quotesListsTradingEnabledSorted() {
        when(instruments.findTradingEnabled()).thenReturn(List.of(instrument("ETHUSD"), instrument("BTCUSD"), instrument("SOLUSD")));
        cache.put(quote("ETHUSD", 1));
        cache.put(quote("BTCUSD", 1));
        cache.put(quote("DOGEUSD", 1));

        List<QuoteView> quotes = controller.list();

        assertThat(quotes).extracting(QuoteView::symbol).containsExactly("BTCUSD", "ETHUSD");
    }

    @Test
    void staleQuoteFlagged() {
        when(instruments.findTradingEnabled()).thenReturn(List.of(instrument("BTCUSD"), instrument("ETHUSD")));
        cache.put(quote("BTCUSD", 11));
        cache.put(quote("ETHUSD", 5));

        List<QuoteView> quotes = controller.list();

        assertThat(quotes).extracting(QuoteView::stale).containsExactly(true, false);
    }

    @Test
    void quoteExactlyAtMaxAgeIsNotStale() {
        when(instruments.findTradingEnabled()).thenReturn(List.of(instrument("BTCUSD")));
        cache.put(quote("BTCUSD", QuoteCache.MAX_AGE.toSeconds()));

        assertThat(controller.list()).extracting(QuoteView::stale).containsExactly(false);
    }

    private static Instrument instrument(String symbol) {
        return new Instrument(Venue.DELTA_TESTNET, symbol, 1, "perpetual_futures", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, true, NOW);
    }

    private static Quote quote(String symbol, long ageSeconds) {
        Instant received = NOW.minusSeconds(ageSeconds);
        return new Quote(symbol, new BigDecimal("100.0"), new BigDecimal("100.5"), new BigDecimal("100.2"), received, received);
    }
}
