package com.vajra.coreservice.position;

import com.vajra.coreservice.connector.Venue;
import java.math.BigDecimal;
import java.time.Instant;

/** Net position per instrument. {@code size} is signed whole contracts: long positive, short negative. */
public record Position(
        Venue venue,
        long instrumentId,
        String symbol,
        long size,
        BigDecimal avgEntryPrice,
        BigDecimal realizedPnl,
        BigDecimal feesPaid,
        Instant updatedAt,
        long version) {

    public static Position flat(Venue venue, long instrumentId, String symbol) {
        return new Position(
                venue, instrumentId, symbol, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Instant.EPOCH, 0);
    }
}
