package com.vajra.brahma.position;

import com.vajra.brahma.connector.Venue;
import java.math.BigDecimal;

/** A position with its live mark; {@code markPrice} and {@code unrealizedPnl} are null when there is no fresh quote. */
public record PositionView(
        Venue venue,
        String symbol,
        long size,
        BigDecimal avgEntryPrice,
        BigDecimal realizedPnl,
        BigDecimal feesPaid,
        BigDecimal markPrice,
        BigDecimal unrealizedPnl) {}
