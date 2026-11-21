package com.vajra.brahma.connector;

import com.vajra.brahma.instrument.Instrument;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** One exchange behind the common model (0007). Trading methods arrive in later slices. */
public interface ExchangeConnector {

    Venue venue();

    /** All tradable products of the venue; {@code tradingEnabled} is true only for {@code enabledSymbols}. */
    List<Instrument> instruments(Set<String> enabledSymbols);

    List<Quote> quotes();

    /** 1-minute candles with open time in {@code [from, to]}, ascending. */
    List<Candle> candles1m(String symbol, Instant from, Instant to);
}
