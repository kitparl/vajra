package com.vajra.coreservice.instrument;

import com.vajra.coreservice.connector.Venue;
import java.math.BigDecimal;
import java.time.Instant;

/** A tradable product in the common model. Sizes are whole contracts; one contract = {@code contractValue} of the base asset. */
public record Instrument(
        Venue venue,
        String symbol,
        long venueProductId,
        String contractType,
        BigDecimal contractValue,
        BigDecimal tickSize,
        BigDecimal minSize,
        BigDecimal makerFee,
        BigDecimal takerFee,
        boolean tradingEnabled,
        Instant updatedAt) {}
