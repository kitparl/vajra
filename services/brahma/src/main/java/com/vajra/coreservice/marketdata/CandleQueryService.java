package com.vajra.coreservice.marketdata;

import com.vajra.coreservice.api.ApiException;
import com.vajra.coreservice.connector.Candle;
import com.vajra.coreservice.connector.ExchangeConnector;
import com.vajra.coreservice.instrument.InstrumentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** Read side for stored candles, with input checks (trust boundary). */
@Service
public class CandleQueryService {

    static final Duration MAX_RANGE = Duration.ofDays(7);
    /** Keeps absurd dates (year 300000) away from Postgres, which would reject them with a 500. */
    static final Duration MAX_FUTURE = Duration.ofDays(1);

    private final ExchangeConnector connector;
    private final InstrumentRepository instruments;
    private final CandleRepository candles;
    private final Clock clock;

    public CandleQueryService(ExchangeConnector connector, InstrumentRepository instruments, CandleRepository candles,
            Clock clock) {
        this.connector = connector;
        this.instruments = instruments;
        this.candles = candles;
        this.clock = clock;
    }

    public List<Candle> find(String symbol, Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("'from' must be before 'to'");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw ApiException.badRequest("range must be at most " + MAX_RANGE.toDays() + " days");
        }
        if (to.isAfter(clock.instant().plus(MAX_FUTURE))) {
            throw ApiException.badRequest("'to' must not be more than 1 day in the future");
        }
        long id = instruments.idOf(connector.venue(), symbol)
                .orElseThrow(() -> ApiException.notFound("unknown symbol: " + symbol));
        return candles.find(id, symbol, from, to);
    }
}
