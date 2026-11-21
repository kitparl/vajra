package com.vajra.brahma.oms;

import com.vajra.brahma.connector.Venue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One execution against an order. {@code (venue, venueFillId)} is unique, which makes fill ingestion replay-safe. */
public record Fill(
        UUID id,
        UUID orderId,
        Venue venue,
        String venueFillId,
        BigDecimal price,
        long size,
        BigDecimal fee,
        String liquidity,
        Instant at) {}
