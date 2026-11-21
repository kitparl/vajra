package com.vajra.coreservice.oms;

import com.vajra.coreservice.connector.Venue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An order in the common model. Sizes are whole contracts; {@code version} is for optimistic locking. */
public record Order(
        UUID id,
        String clientOrderId,
        String idempotencyKey,
        Venue venue,
        long instrumentId,
        String symbol,
        Side side,
        OrderType type,
        long size,
        BigDecimal limitPrice,
        TimeInForce timeInForce,
        boolean reduceOnly,
        OrderState state,
        long filledSize,
        BigDecimal avgFillPrice,
        BigDecimal fees,
        String venueOrderId,
        OrderSource source,
        String reason,
        Instant createdAt,
        Instant updatedAt,
        long version) {}
