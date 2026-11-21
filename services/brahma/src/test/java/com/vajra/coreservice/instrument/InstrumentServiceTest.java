package com.vajra.coreservice.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.vajra.coreservice.connector.Candle;
import com.vajra.coreservice.connector.ExchangeConnector;
import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.connector.delta.DeltaApiException;
import com.vajra.coreservice.marketdata.MarketDataProperties;
import java.math.BigDecimal;
import com.vajra.coreservice.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class InstrumentServiceTest {

    private static final MarketDataProperties PROPS = new MarketDataProperties(Set.of("BTCUSD", "ETHUSD"));

    private final InstrumentRepository repository = mock(InstrumentRepository.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-21T10:00:00Z"));

    @Test
    @SuppressWarnings("unchecked")
    void missingEnabledSymbolIsSkipped(CapturedOutput output) {
        InstrumentService service = new InstrumentService(new FakeConnector(List.of(btc()), null), repository, PROPS, clock);

        service.refresh();

        ArgumentCaptor<List<Instrument>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(saved.capture());
        assertThat(saved.getValue()).extracting(Instrument::symbol).containsExactly("BTCUSD");
        assertThat(output).contains("WARN").contains("ETHUSD");
    }

    @Test
    void refreshSwallowsDeltaErrors() {
        DeltaApiException boom = new DeltaApiException("down", false, null);
        InstrumentService service = new InstrumentService(new FakeConnector(List.of(), boom), repository, PROPS, clock);

        assertThatCode(service::scheduledRefresh).doesNotThrowAnyException();
        verify(repository, never()).upsertAll(any());
    }

    @Test
    void failedRefreshRetriesOnTheNextRun() {
        FakeConnector connector = new FakeConnector(List.of(btc()), new DeltaApiException("blip", false, null));
        InstrumentService service = new InstrumentService(connector, repository, PROPS, clock);
        service.scheduledRefresh();

        connector.failure = null;
        clock.advance(Duration.ofMinutes(1));
        service.scheduledRefresh();

        verify(repository).upsertAll(any());
    }

    @Test
    void successfulRefreshIsNotRepeatedForSixHours() {
        FakeConnector connector = new FakeConnector(List.of(btc()), null);
        InstrumentService service = new InstrumentService(connector, repository, PROPS, clock);
        service.scheduledRefresh();
        clock.advance(Duration.ofHours(5));
        service.scheduledRefresh();
        assertThat(connector.calls).isEqualTo(1);

        clock.advance(Duration.ofHours(1));
        service.scheduledRefresh();

        assertThat(connector.calls).isEqualTo(2);
    }

    @Test
    void scheduledRefreshSwallowsDatabaseErrors() {
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
                .when(repository).upsertAll(any());
        InstrumentService service = new InstrumentService(new FakeConnector(List.of(btc()), null), repository, PROPS, clock);

        assertThatCode(service::scheduledRefresh).doesNotThrowAnyException();
    }

    private static Instrument btc() {
        return new Instrument(Venue.DELTA_TESTNET, "BTCUSD", 84, "perpetual_futures", new BigDecimal("0.001"),
                new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"), new BigDecimal("0.0005"), true,
                Instant.parse("2026-11-21T10:00:00Z"));
    }

    private static final class FakeConnector implements ExchangeConnector {
        final List<Instrument> instruments;
        RuntimeException failure;
        int calls;

        FakeConnector(List<Instrument> instruments, RuntimeException failure) {
            this.instruments = instruments;
            this.failure = failure;
        }

        @Override
        public Venue venue() {
            return Venue.DELTA_TESTNET;
        }

        @Override
        public List<Instrument> instruments(Set<String> enabledSymbols) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return instruments;
        }

        @Override
        public List<Quote> quotes() {
            return List.of();
        }

        @Override
        public List<Candle> candles1m(String symbol, Instant from, Instant to) {
            return List.of();
        }
    }
}
