package com.vajra.coreservice.connector.delta;

import java.math.BigDecimal;

/** A Delta order. {@code state}: open, pending, closed or cancelled. Price and commission are null until a fill. */
public record DeltaOrder(
        long id,
        String clientOrderId,
        String state,
        long size,
        long unfilledSize,
        BigDecimal averageFillPrice,
        BigDecimal paidCommission,
        long productId) {}
