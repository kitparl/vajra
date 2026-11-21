package com.vajra.brahma.oms;

import com.vajra.brahma.connector.Venue;

/** Where new orders go ({@code vajra.trading.mode}, env {@code TRADING_MODE}). PAPER is the default. */
public enum TradingMode {
    PAPER(Venue.PAPER),
    DELTA_TESTNET(Venue.DELTA_TESTNET);

    private final Venue venue;

    TradingMode(Venue venue) {
        this.venue = venue;
    }

    public Venue venue() {
        return venue;
    }
}
