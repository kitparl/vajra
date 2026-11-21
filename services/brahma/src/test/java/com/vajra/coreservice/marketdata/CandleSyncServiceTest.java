package com.vajra.coreservice.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vajra.coreservice.connector.Candle;
import com.vajra.coreservice.connector.ExchangeConnector;
import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.connector.delta.DeltaApiException;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CandleSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:30Z");

    private final RecordingConnector connector = new RecordingConnector();
    private final CandleRepository candles = mock(CandleRepository.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final CandleSyncService service = new CandleSyncService(connector, candles, instruments, new MutableClock(NOW));

    @Test
    void emptyStoreBackfillsSevenDaysInChunks() {
        when(instruments.idOf(Venue.DELTA_TESTNET, "BTCUSD")).thenReturn(Optional.of(1L));
        when(candles.lastOpenTime(1L)).thenReturn(Optional.empty());

        service.sync(instrument("BTCUSD"));

        assertThat(connector.calls).hasSize(6);
        assertThat(connector.calls.getFirst().from()).isEqualTo(Instant.parse("2026-11-14T10:00:00Z"));
        assertThat(connector.calls.getLast().to()).isEqualTo(NOW);
        assertThat(connector.calls).allSatisfy(c ->
                assertThat(Duration.between(c.from(), c.to())).isLessThan(Duration.ofMinutes(2000)));
        for (int i = 1; i < connector.calls.size(); i++) {
            assertThat(connector.calls.get(i).from()).isAfter(connector.calls.get(i - 1).to());
        }
        verify(candles, org.mockito.Mockito.times(6)).upsertAll(eq(1L), anyList());
    }

    @Test
    void syncStartsFromLastStoredCandle() {
        when(instruments.idOf(Venue.DELTA_TESTNET, "BTCUSD")).thenReturn(Optional.of(1L));
        when(candles.lastOpenTime(1L)).thenReturn(Optional.of(Instant.parse("2026-11-21T09:55:00Z")));

        service.sync(instrument("BTCUSD"));

        assertThat(connector.calls).containsExactly(new Call("BTCUSD", Instant.parse("2026-11-21T09:55:00Z"), NOW));
    }

    @Test
    void oneSymbolFailingDoesNotStopOthers() {
        connector.failFor = "BTCUSD";
        when(instruments.findTradingEnabled()).thenReturn(List.of(instrument("BTCUSD"), instrument("ETHUSD")));
        when(instruments.idOf(eq(Venue.DELTA_TESTNET), org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.of(1L));
        when(candles.lastOpenTime(1L)).thenReturn(Optional.of(Instant.parse("2026-11-21T09:59:00Z")));

        service.syncAll();

        assertThat(connector.calls).extracting(Call::symbol).containsExactly("BTCUSD", "ETHUSD");
        verify(candles).upsertAll(eq(1L), anyList());
    }

    private static Instrument instrument(String symbol) {
        return new Instrument(Venue.DELTA_TESTNET, symbol, 1, "perpetual_futures", new BigDecimal("0.001"),
                new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"), new BigDecimal("0.0005"), true, NOW);
    }

    private record Call(String symbol, Instant from, Instant to) {}

    private static final class RecordingConnector implements ExchangeConnector {
        final List<Call> calls = new ArrayList<>();
        String failFor;

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
            return List.of();
        }

        @Override
        public List<Candle> candles1m(String symbol, Instant from, Instant to) {
            calls.add(new Call(symbol, from, to));
            if (symbol.equals(failFor)) {
                throw new DeltaApiException("down", false, null);
            }
            return List.of(new Candle(symbol, from, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        }
    }
}
