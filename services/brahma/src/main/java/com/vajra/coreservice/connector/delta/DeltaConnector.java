package com.vajra.coreservice.connector.delta;

import com.vajra.coreservice.connector.Candle;
import com.vajra.coreservice.connector.ExchangeConnector;
import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.instrument.Instrument;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Maps Delta testnet data to the common model. */
@Component
public class DeltaConnector implements ExchangeConnector {

    /** Delta order sizes are whole contracts; the products API has no min-size field (checked 2026-11-21). */
    private static final BigDecimal MIN_SIZE = BigDecimal.ONE;

    private final DeltaPublicClient client;
    private final Clock clock;

    public DeltaConnector(DeltaPublicClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public Venue venue() {
        return Venue.DELTA_TESTNET;
    }

    @Override
    public List<Instrument> instruments(Set<String> enabledSymbols) {
        Instant now = clock.instant();
        return client.livePerpetuals().stream()
                .map(p -> new Instrument(venue(), p.symbol(), p.id(), p.contractType(), p.contractValue(), p.tickSize(),
                        MIN_SIZE, p.makerCommissionRate(), p.takerCommissionRate(), enabledSymbols.contains(p.symbol()), now))
                .toList();
    }

    @Override
    public List<Quote> quotes() {
        Instant now = clock.instant();
        return client.perpetualTickers().stream()
                .map(t -> new Quote(t.symbol(), t.bestBid(), t.bestAsk(), t.markPrice(), t.timestamp(), now))
                .toList();
    }

    @Override
    public List<Candle> candles1m(String symbol, Instant from, Instant to) {
        return client.candles1m(symbol, from, to).stream()
                .map(c -> new Candle(symbol, c.time(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                .toList();
    }
}
