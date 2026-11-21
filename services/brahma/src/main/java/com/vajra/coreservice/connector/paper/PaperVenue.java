package com.vajra.coreservice.connector.paper;

import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.connector.TradingVenue;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.connector.VenueResult;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.marketdata.QuoteCache;
import com.vajra.coreservice.oms.Order;
import com.vajra.coreservice.oms.OrderType;
import com.vajra.coreservice.oms.Side;
import com.vajra.coreservice.oms.TimeInForce;
import com.vajra.coreservice.position.PositionMath;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Simulated exchange: fills orders against live quotes from the {@link QuoteCache}. */
@Component
public class PaperVenue implements TradingVenue {

    static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(10);
    private static final String TAKER = "TAKER";
    private static final String MAKER = "MAKER";

    private final QuoteCache quotes;

    public PaperVenue(QuoteCache quotes) {
        this.quotes = quotes;
    }

    @Override
    public Venue venue() {
        return Venue.PAPER;
    }

    @Override
    public VenueResult place(Order order, Instrument instrument) {
        Optional<Quote> quote = freshQuote(order.symbol());
        if (quote.isEmpty()) {
            return new VenueResult.Rejected("STALE_PRICE");
        }
        BigDecimal touch = touchPrice(order.side(), quote.get());
        if (order.type() == OrderType.MARKET || isMarketable(order, quote.get())) {
            return filled(order, instrument, touch, instrument.takerFee(), TAKER);
        }
        return order.timeInForce() == TimeInForce.IOC
                ? new VenueResult.Expired(venueOrderId(order))
                : new VenueResult.Resting(venueOrderId(order));
    }

    @Override
    public VenueResult cancel(Order order) {
        return new VenueResult.Cancelled(venueOrderId(order));
    }

    @Override
    public Optional<VenueResult> checkResting(Order order, Instrument instrument) {
        return freshQuote(order.symbol())
                .filter(q -> isMarketable(order, q))
                .map(q -> filled(order, instrument, order.limitPrice(), instrument.makerFee(), MAKER));
    }

    private Optional<Quote> freshQuote(String symbol) {
        return quotes.fresh(symbol, MAX_QUOTE_AGE).filter(q -> isPositive(q.bestBid()) && isPositive(q.bestAsk()));
    }

    private static boolean isPositive(BigDecimal price) {
        return price != null && price.signum() > 0;
    }

    /** The side of the book an aggressive order trades against. */
    private static BigDecimal touchPrice(Side side, Quote quote) {
        return side == Side.BUY ? quote.bestAsk() : quote.bestBid();
    }

    /** Limit order crosses the book: BUY limit at or above ask, SELL limit at or below bid. */
    private static boolean isMarketable(Order order, Quote quote) {
        if (order.type() != OrderType.LIMIT) {
            return false;
        }
        int cmp = order.limitPrice().compareTo(touchPrice(order.side(), quote));
        return order.side() == Side.BUY ? cmp >= 0 : cmp <= 0;
    }

    private static VenueResult filled(Order order, Instrument instrument, BigDecimal price, BigDecimal rate, String liquidity) {
        BigDecimal fee = PositionMath.fee(order.size(), price, instrument.contractValue(), rate);
        return new VenueResult.Filled(venueOrderId(order), "paper-fill-" + order.id(), price, fee, liquidity);
    }

    private static String venueOrderId(Order order) {
        return "paper-" + order.id();
    }
}
