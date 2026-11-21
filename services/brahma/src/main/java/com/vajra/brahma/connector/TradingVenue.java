package com.vajra.brahma.connector;

import com.vajra.brahma.instrument.Instrument;
import com.vajra.brahma.oms.Order;
import java.util.Optional;

/** Order placement side of a venue; market data stays in {@link ExchangeConnector}. */
public interface TradingVenue {

    Venue venue();

    VenueResult place(Order order, Instrument instrument);

    VenueResult cancel(Order order);

    /** Checks whether a resting order has filled; empty when it has not (or no fresh quote exists). */
    Optional<VenueResult> checkResting(Order order, Instrument instrument);
}
