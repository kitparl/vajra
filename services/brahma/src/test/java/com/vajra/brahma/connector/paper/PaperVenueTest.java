package com.vajra.brahma.connector.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.vajra.brahma.connector.Quote;
import com.vajra.brahma.connector.Venue;
import com.vajra.brahma.connector.VenueResult;
import com.vajra.brahma.instrument.Instrument;
import com.vajra.brahma.marketdata.QuoteCache;
import com.vajra.brahma.oms.Order;
import com.vajra.brahma.oms.OrderSource;
import com.vajra.brahma.oms.OrderState;
import com.vajra.brahma.oms.OrderType;
import com.vajra.brahma.oms.Side;
import com.vajra.brahma.oms.TimeInForce;
import com.vajra.brahma.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaperVenueTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private final MutableClock clock = new MutableClock(T0);
    private final QuoteCache cache = new QuoteCache(clock);
    private final PaperVenue venue = new PaperVenue(cache);
    private final Instrument btc = new Instrument(
            Venue.DELTA_TESTNET, "BTCUSD", 27L, "perpetual_futures", new BigDecimal("0.001"),
            new BigDecimal("0.5"), BigDecimal.ONE, new BigDecimal("0.0002"), new BigDecimal("0.0005"),
            true, T0);

    private void quote(String bid, String ask, Instant receivedAt) {
        cache.put(new Quote("BTCUSD", bid == null ? null : new BigDecimal(bid),
                ask == null ? null : new BigDecimal(ask), null, receivedAt, receivedAt));
    }

    private Order order(Side side, OrderType type, String limit, TimeInForce tif) {
        return new Order(UUID.randomUUID(), "cid", "key", Venue.PAPER, 1L, "BTCUSD", side, type, 1L,
                limit == null ? null : new BigDecimal(limit), tif, false, OrderState.SENT, 0L, null,
                BigDecimal.ZERO, null, OrderSource.MANUAL, null, T0, T0, 0L);
    }

    @Test
    void marketBuyFillsAtAskWithTakerFee() {
        quote("50000", "50010", T0);
        Order o = order(Side.BUY, OrderType.MARKET, null, TimeInForce.IOC);
        VenueResult.Filled f = (VenueResult.Filled) venue.place(o, btc);
        assertThat(f.price()).isEqualByComparingTo("50010");
        assertThat(f.fee()).isEqualByComparingTo("0.0250050");
        assertThat(f.liquidity()).isEqualTo("TAKER");
        assertThat(f.venueOrderId()).isEqualTo("paper-" + o.id());
        assertThat(f.venueFillId()).isEqualTo("paper-fill-" + o.id());
    }

    @Test
    void marketSellFillsAtBid() {
        quote("50000", "50010", T0);
        VenueResult.Filled f = (VenueResult.Filled) venue.place(order(Side.SELL, OrderType.MARKET, null, TimeInForce.IOC), btc);
        assertThat(f.price()).isEqualByComparingTo("50000");
    }

    @Test
    void marketableLimitFillsAsTaker() {
        quote("50000", "50010", T0);
        VenueResult.Filled f = (VenueResult.Filled) venue.place(order(Side.BUY, OrderType.LIMIT, "50100", TimeInForce.GTC), btc);
        assertThat(f.price()).isEqualByComparingTo("50010");
        assertThat(f.liquidity()).isEqualTo("TAKER");
    }

    @Test
    void restingGtcLimit() {
        quote("50000", "50010", T0);
        Order o = order(Side.BUY, OrderType.LIMIT, "49900", TimeInForce.GTC);
        assertThat(venue.place(o, btc)).isEqualTo(new VenueResult.Resting("paper-" + o.id()));
    }

    @Test
    void iocLimitNotMarketableExpires() {
        quote("50000", "50010", T0);
        Order o = order(Side.BUY, OrderType.LIMIT, "49900", TimeInForce.IOC);
        assertThat(venue.place(o, btc)).isEqualTo(new VenueResult.Expired("paper-" + o.id()));
    }

    @Test
    void checkRestingFillsAtLimitAsMaker() {
        quote("50000", "50010", T0);
        Order o = order(Side.BUY, OrderType.LIMIT, "49900", TimeInForce.GTC);
        assertThat(venue.checkResting(o, btc)).isEmpty();
        quote("49880", "49890", T0);
        Optional<VenueResult> r = venue.checkResting(o, btc);
        VenueResult.Filled f = (VenueResult.Filled) r.orElseThrow();
        assertThat(f.price()).isEqualByComparingTo("49900");
        assertThat(f.fee()).isEqualByComparingTo("0.0099800");
        assertThat(f.liquidity()).isEqualTo("MAKER");
    }

    @Test
    void staleQuoteRejects() {
        quote("50000", "50010", T0.minus(Duration.ofSeconds(11)));
        assertThat(venue.place(order(Side.BUY, OrderType.MARKET, null, TimeInForce.IOC), btc))
                .isEqualTo(new VenueResult.Rejected("STALE_PRICE"));
    }

    @Test
    void nullBidAskCountsAsMissing() {
        quote("50000", null, T0);
        assertThat(venue.place(order(Side.BUY, OrderType.MARKET, null, TimeInForce.IOC), btc))
                .isEqualTo(new VenueResult.Rejected("STALE_PRICE"));
    }

    @Test
    void matcherSkipsWithoutFreshQuote() {
        quote("49880", "49890", T0.minus(Duration.ofSeconds(11)));
        assertThat(venue.checkResting(order(Side.BUY, OrderType.LIMIT, "49900", TimeInForce.GTC), btc)).isEmpty();
    }

    @Test
    void cancelReturnsCancelled() {
        Order o = order(Side.BUY, OrderType.LIMIT, "49900", TimeInForce.GTC);
        assertThat(venue.cancel(o)).isEqualTo(new VenueResult.Cancelled("paper-" + o.id()));
    }

    @Test
    void nonPositivePriceCountsAsMissing() {
        quote("0", "50010", T0);
        assertThat(venue.place(order(Side.SELL, OrderType.MARKET, null, TimeInForce.IOC), btc))
                .isEqualTo(new VenueResult.Rejected("STALE_PRICE"));
        quote("50000", "-1", T0);
        assertThat(venue.place(order(Side.BUY, OrderType.MARKET, null, TimeInForce.IOC), btc))
                .isEqualTo(new VenueResult.Rejected("STALE_PRICE"));
    }

    @Test
    void matcherSkipsNonPositivePrice() {
        quote("0", "0", T0);
        assertThat(venue.checkResting(order(Side.BUY, OrderType.LIMIT, "49900", TimeInForce.GTC), btc)).isEmpty();
    }
}
