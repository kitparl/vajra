package com.vajra.coreservice.api;

import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.marketdata.QuoteCache;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class QuoteController {

    private final QuoteCache quotes;
    private final InstrumentRepository instruments;

    QuoteController(QuoteCache quotes, InstrumentRepository instruments) {
        this.quotes = quotes;
        this.instruments = instruments;
    }

    /** Latest quote per trading-enabled symbol, sorted by symbol. A symbol without a quote yet is omitted. */
    @GetMapping("/api/v1/quotes")
    List<QuoteView> list() {
        Map<String, Quote> bySymbol = quotes.all().stream().collect(Collectors.toMap(Quote::symbol, Function.identity()));
        return instruments.findTradingEnabled().stream()
                .map(Instrument::symbol)
                .sorted(Comparator.naturalOrder())
                .map(bySymbol::get)
                .filter(Objects::nonNull)
                .map(q -> QuoteView.of(q, quotes.isStale(q, QuoteCache.MAX_AGE)))
                .toList();
    }
}
