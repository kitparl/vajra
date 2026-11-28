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
import java.time.temporal.ChronoUnit;
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
    void emptyTableBackfills90Days() {
        when(instruments.idOf(Venue.DELTA_TESTNET, "BTCUSD")).thenReturn(Optional.of(1L));
        when(candles.lastOpenTime(1L)).thenReturn(Optional.empty());

        service.sync(instrument("BTCUSD"));

        assertThat(connector.calls).hasSize(65);
        assertThat(connector.calls.getFirst().from()).isEqualTo(Instant.parse("2026-08-23T10:00:00Z"));
        assertThat(connector.calls.getLast().to()).isEqualTo(NOW);
        assertThat(connector.calls).allSatisfy(c ->
                assertThat(Duration.between(c.from(), c.to())).isLessThan(Duration.ofMinutes(2000)));
        for (int i = 1; i < connector.calls.size(); i++) {
            assertThat(connector.calls.get(i).from()).isAfter(connector.calls.get(i - 1).to());
        }
        verify(candles, org.mockito.Mockito.times(65)).upsertAll(eq(1L), anyList());
    }

    @Test
    void backwardFillRunsOnceWhenHistoryIsShort() {
        stubStored(NOW.minus(Duration.ofDays(7)), NOW.minus(Duration.ofMinutes(1)));
        Instant start = NOW.minus(Duration.ofDays(90)).truncatedTo(ChronoUnit.MINUTES);
        Instant first = NOW.minus(Duration.ofDays(7));

        service.syncAll();

        List<Call> backward = connector.calls.stream().filter(c -> !c.to().isAfter(first)).toList();
        assertThat(backward).isNotEmpty();
        assertThat(backward.getFirst().to()).isEqualTo(first.minusSeconds(1));
        assertThat(backward.getLast().from()).isEqualTo(start);
        assertThat(backward).allSatisfy(c ->
                assertThat(Duration.between(c.from(), c.to())).isLessThan(Duration.ofMinutes(2000)));
        assertThat(connector.calls.getLast()).isEqualTo(new Call("BTCUSD", NOW.minus(Duration.ofMinutes(1)), NOW));
        assertThat(connector.calls).hasSize(backward.size() + 1);

        connector.calls.clear();
        service.syncAll();

        assertThat(connector.calls).containsExactly(new Call("BTCUSD", NOW.minus(Duration.ofMinutes(1)), NOW));
    }

    @Test
    void noBackwardFillWhenHistoryIsLongEnough() {
        stubStored(NOW.minus(Duration.ofDays(90)).truncatedTo(ChronoUnit.MINUTES), NOW.minus(Duration.ofMinutes(1)));

        service.syncAll();

        assertThat(connector.calls).containsExactly(new Call("BTCUSD", NOW.minus(Duration.ofMinutes(1)), NOW));
    }

    @Test
    void backwardFillFailureIsRetriedNextRun() {
        Instant first = NOW.minus(Duration.ofDays(7));
        Instant start = NOW.minus(Duration.ofDays(90)).truncatedTo(ChronoUnit.MINUTES);
        Call forward = new Call("BTCUSD", NOW.minus(Duration.ofMinutes(1)), NOW);
        stubStored(first, NOW.minus(Duration.ofMinutes(1)));
        connector.failOnce = true;

        service.syncAll();

        Instant newestFrom = first.minusSeconds(1).plusSeconds(1).minus(CandleSyncService.MAX_WINDOW);
        assertThat(connector.calls).containsExactly(new Call("BTCUSD", newestFrom, first.minusSeconds(1)), forward);
        connector.calls.clear();

        service.syncAll();

        assertThat(connector.calls.getFirst()).isEqualTo(new Call("BTCUSD", newestFrom, first.minusSeconds(1)));
        assertThat(connector.calls.get(connector.calls.size() - 2).from()).isEqualTo(start);
        assertThat(connector.calls.getLast()).isEqualTo(forward);
    }

    @Test
    void backwardFillStopsWhenDeltaReturnsNothing() {
        Instant first = NOW.minus(Duration.ofDays(7));
        Call forward = new Call("BTCUSD", NOW.minus(Duration.ofMinutes(1)), NOW);
        stubStored(first, NOW.minus(Duration.ofMinutes(1)));
        connector.emptyBefore = NOW.minus(Duration.ofDays(1));

        service.syncAll();

        assertThat(connector.calls).hasSize(2);
        assertThat(connector.calls.getFirst().to()).isEqualTo(first.minusSeconds(1));
        assertThat(connector.calls.getLast()).isEqualTo(forward);
        connector.calls.clear();

        service.syncAll();

        assertThat(connector.calls).containsExactly(forward);
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

    private void stubStored(Instant first, Instant last) {
        when(instruments.findTradingEnabled()).thenReturn(List.of(instrument("BTCUSD")));
        when(instruments.idOf(Venue.DELTA_TESTNET, "BTCUSD")).thenReturn(Optional.of(1L));
        when(candles.firstOpenTime(1L)).thenReturn(Optional.of(first));
        when(candles.lastOpenTime(1L)).thenReturn(Optional.of(last));
    }

    private static Instrument instrument(String symbol) {
        return new Instrument(Venue.DELTA_TESTNET, symbol, 1, "perpetual_futures", new BigDecimal("0.001"),
                new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"), new BigDecimal("0.0005"), true, NOW);
    }

    private record Call(String symbol, Instant from, Instant to) {}

    private static final class RecordingConnector implements ExchangeConnector {
        final List<Call> calls = new ArrayList<>();
        String failFor;
        boolean failOnce;
        Instant emptyBefore = Instant.MIN;

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
            if (symbol.equals(failFor) || failOnce) {
                failOnce = false;
                throw new DeltaApiException("down", false, null);
            }
            if (to.isBefore(emptyBefore)) {
                return List.of();
            }
            return List.of(new Candle(symbol, from, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        }
    }
}
