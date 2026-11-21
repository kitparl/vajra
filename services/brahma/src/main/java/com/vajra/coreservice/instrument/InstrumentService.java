package com.vajra.coreservice.instrument;

import com.vajra.coreservice.connector.ExchangeConnector;
import com.vajra.coreservice.marketdata.MarketDataProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Keeps {@code core.instrument} in sync with the venue's product list. */
@Service
public class InstrumentService {

    static final Duration REFRESH_INTERVAL = Duration.ofHours(6);

    private static final Logger log = LoggerFactory.getLogger(InstrumentService.class);

    private final ExchangeConnector connector;
    private final InstrumentRepository repository;
    private final MarketDataProperties properties;
    private final Clock clock;
    private volatile Instant lastSuccess;

    public InstrumentService(ExchangeConnector connector, InstrumentRepository repository, MarketDataProperties properties,
            Clock clock) {
        this.connector = connector;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public void refresh() {
        List<Instrument> instruments = connector.instruments(properties.symbols());
        repository.upsertAll(instruments);
        Set<String> found = instruments.stream().map(Instrument::symbol).collect(Collectors.toSet());
        properties.symbols().stream()
                .filter(symbol -> !found.contains(symbol))
                .forEach(symbol -> log.warn("Enabled symbol {} not found on {}; it will not be traded", symbol, connector.venue()));
        log.info("Instrument refresh: {} products from {}", instruments.size(), connector.venue());
    }

    public List<Instrument> all() {
        return repository.findAll();
    }

    /** Runs every minute but refreshes only every 6 h; until a refresh has succeeded it retries every minute. */
    @Scheduled(initialDelay = 0, fixedDelayString = "PT60S")
    public void scheduledRefresh() {
        Instant now = clock.instant();
        if (lastSuccess != null && Duration.between(lastSuccess, now).compareTo(REFRESH_INTERVAL) < 0) {
            return;
        }
        try {
            refresh();
            lastSuccess = now;
        } catch (RuntimeException e) {
            log.warn("Instrument refresh failed, will retry in a minute: {}", e.getMessage());
        }
    }
}
