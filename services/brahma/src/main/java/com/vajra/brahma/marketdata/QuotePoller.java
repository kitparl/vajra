package com.vajra.brahma.marketdata;

import com.vajra.brahma.connector.ExchangeConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls venue tickers every 2 s into {@link QuoteCache}. A failed poll keeps the last quote (it turns stale on its own). */
@Component
public class QuotePoller {

    private static final Logger log = LoggerFactory.getLogger(QuotePoller.class);

    private final ExchangeConnector connector;
    private final QuoteCache cache;
    private final MarketDataProperties properties;

    public QuotePoller(ExchangeConnector connector, QuoteCache cache, MarketDataProperties properties) {
        this.connector = connector;
        this.cache = cache;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "PT2S")
    public void poll() {
        try {
            connector.quotes().stream()
                    .filter(q -> properties.symbols().contains(q.symbol()))
                    .forEach(cache::put);
        } catch (RuntimeException e) {
            log.warn("Quote poll failed: {}", e.getMessage());
        }
    }
}
