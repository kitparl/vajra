package com.vajra.coreservice.connector.delta;

import java.math.BigDecimal;
import java.time.Instant;

/** One Delta fill. {@code role} is maker or taker. */
public record DeltaFill(
        String id, long orderId, long size, BigDecimal price, BigDecimal commission, String role, Instant createdAt) {}
