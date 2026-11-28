package com.vajra.coreservice.marketdata;

import com.vajra.coreservice.connector.Candle;
import com.vajra.coreservice.connector.ExchangeConnector;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps {@code core.candle_1m} up to date. Each run continues from the last stored candle (inclusive, so the
 * then-open candle is overwritten), which also backfills any gap after downtime. The target history is
 * {@link #HISTORY} (90 days); an instrument with a shorter history gets a one-time-per-process backward fill from
 * {@code now - HISTORY} up to its first stored candle (upserts are idempotent).
 */
@Service
public class CandleSyncService {

    static final Duration HISTORY = Duration.ofDays(90);
    /** Delta returns at most 2000 candles per call. */
    static final Duration MAX_WINDOW = Duration.ofMinutes(2000);

    private static final Logger log = LoggerFactory.getLogger(CandleSyncService.class);

    private final ExchangeConnector connector;
    private final CandleRepository candles;
    private final InstrumentRepository instruments;
    private final Clock clock;
    /** Symbols whose backward fill finished in this process; set only after success so failures are retried. */
    private final Set<String> historyComplete = ConcurrentHashMap.newKeySet();

    public CandleSyncService(ExchangeConnector connector, CandleRepository candles, InstrumentRepository instruments, Clock clock) {
        this.connector = connector;
        this.candles = candles;
        this.instruments = instruments;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "PT60S")
    public void syncAll() {
        for (Instrument instrument : instruments.findTradingEnabled()) {
            try {
                sync(instrument);
            } catch (RuntimeException e) {
                log.warn("Candle sync failed for {}, will retry on the next run: {}", instrument.symbol(), e.getMessage());
            }
        }
    }

    public void sync(Instrument instrument) {
        Optional<Long> id = instruments.idOf(instrument.venue(), instrument.symbol());
        if (id.isEmpty()) {
            log.warn("Instrument {} not stored yet; skipping candle sync", instrument.symbol());
            return;
        }
        Instant now = clock.instant();
        try {
            backfillHistory(instrument.symbol(), id.get(), now);
        } catch (RuntimeException e) {
            log.warn("Candle history backfill failed for {}, will retry on the next run: {}", instrument.symbol(), e.getMessage());
        }
        Instant cursor = candles.lastOpenTime(id.get())
                .orElseGet(() -> now.minus(HISTORY).truncatedTo(ChronoUnit.MINUTES));
        while (cursor.isBefore(now)) {
            Instant windowEnd = min(cursor.plus(MAX_WINDOW).minusSeconds(1), now);
            List<Candle> batch = connector.candles1m(instrument.symbol(), cursor, windowEnd);
            candles.upsertAll(id.get(), batch);
            cursor = cursor.plus(MAX_WINDOW);
        }
    }

    /**
     * Walks backward from the earliest stored candle towards {@code now - HISTORY}, newest window first, and stops
     * early when Delta returns nothing (no older data exists). A failure leaves the symbol unmarked so it is retried.
     */
    private void backfillHistory(String symbol, long instrumentId, Instant now) {
        if (historyComplete.contains(symbol)) {
            return;
        }
        Instant start = now.minus(HISTORY).truncatedTo(ChronoUnit.MINUTES);
        Optional<Instant> first = candles.firstOpenTime(instrumentId);
        if (first.isPresent() && first.get().isAfter(start)) {
            Instant windowEnd = first.get().minusSeconds(1);
            while (!windowEnd.isBefore(start)) {
                Instant windowStart = max(start, windowEnd.plusSeconds(1).minus(MAX_WINDOW));
                List<Candle> batch = connector.candles1m(symbol, windowStart, windowEnd);
                if (batch.isEmpty()) {
                    break;
                }
                candles.upsertAll(instrumentId, batch);
                windowEnd = windowStart.minusSeconds(1);
            }
        }
        historyComplete.add(symbol);
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
