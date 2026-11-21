package com.vajra.coreservice.api;

import com.vajra.coreservice.connector.Candle;
import com.vajra.coreservice.marketdata.CandleQueryService;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CandleController {

    private final CandleQueryService candles;

    CandleController(CandleQueryService candles) {
        this.candles = candles;
    }

    /** Candles with {@code from <= openTime < to}; {@code from}/{@code to} are ISO-8601 instants. */
    @GetMapping("/api/v1/candles")
    List<Candle> list(@RequestParam String symbol, @RequestParam Instant from, @RequestParam Instant to) {
        return candles.find(symbol, from, to);
    }
}
