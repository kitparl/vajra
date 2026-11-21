package com.vajra.brahma.connector;

import java.math.BigDecimal;

/** Outcome of a venue call. */
public sealed interface VenueResult {

    record Filled(String venueOrderId, String venueFillId, BigDecimal price, BigDecimal fee, String liquidity)
            implements VenueResult {}

    record Resting(String venueOrderId) implements VenueResult {}

    record Expired(String venueOrderId) implements VenueResult {}

    record Cancelled(String venueOrderId) implements VenueResult {}

    record Rejected(String reason) implements VenueResult {}
}
