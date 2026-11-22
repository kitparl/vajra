package com.vajra.coreservice.connector.delta;

import com.vajra.coreservice.connector.TradingVenue;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.connector.VenueResult;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.oms.Order;
import com.vajra.coreservice.oms.OrderType;
import com.vajra.coreservice.oms.TimeInForce;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Trades on Delta Exchange India testnet. A retryable Delta failure (timeout, I/O, 5xx) is rethrown so the order goes
 * UNKNOWN and is never re-sent; a definite Delta rejection becomes {@link VenueResult.Rejected}.
 */
@Component
@ConditionalOnProperty(name = "vajra.trading.mode", havingValue = "DELTA_TESTNET")
public class DeltaTradingVenue implements TradingVenue {

    static final String RATE_LIMITED = "RATE_LIMITED";
    static final String FILL_PENDING = "FILL_PENDING";
    static final String PARTIAL_FILL_UNSUPPORTED = "PARTIAL_FILL_UNSUPPORTED";
    private static final Logger log = LoggerFactory.getLogger(DeltaTradingVenue.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(300);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration FILL_LOOKBACK = Duration.ofMinutes(1);

    private final DeltaPrivateClient client;
    private final InstrumentRepository instruments;
    private final DeltaRateLimitGuard guard;
    private final Clock clock;
    private final LongConsumer sleeperMillis;

    @Autowired
    public DeltaTradingVenue(
            DeltaPrivateClient client, InstrumentRepository instruments, DeltaRateLimitGuard guard, Clock clock) {
        this(client, instruments, guard, clock, DeltaTradingVenue::sleep);
    }

    DeltaTradingVenue(DeltaPrivateClient client, InstrumentRepository instruments, DeltaRateLimitGuard guard, Clock clock,
            LongConsumer sleeperMillis) {
        this.client = client;
        this.instruments = instruments;
        this.guard = guard;
        this.clock = clock;
        this.sleeperMillis = sleeperMillis;
    }

    @Override
    public Venue venue() {
        return Venue.DELTA_TESTNET;
    }

    @Override
    public VenueResult place(Order order, Instrument instrument) {
        if (guard.isPaused()) {
            return new VenueResult.Rejected(RATE_LIMITED);
        }
        DeltaOrder placed;
        try {
            placed = client.placeOrder(body(order, instrument));
        } catch (DeltaApiException e) {
            if (e.retryable()) {
                throw e;
            }
            return new VenueResult.Rejected(e.code());
        }
        // From here the order exists at Delta: never Rejected, whatever goes wrong. Resting lets the checker resolve it.
        try {
            DeltaOrder settled = isMarketOrIoc(order) && isLive(placed) ? pollUntilSettled(order, placed) : placed;
            return toResult(settled, order);
        } catch (RuntimeException e) {
            String code = e instanceof DeltaApiException delta ? delta.code() : "-";
            log.warn("Order {} accepted by Delta as {} but not settled ({}, code {}); left resting for the checker",
                    order.id(), placed.id(), e.getClass().getSimpleName(), code);
            return new VenueResult.Resting(Long.toString(placed.id()));
        }
    }

    @Override
    public VenueResult cancel(Order order) {
        DeltaOrder cancelled = client.cancelOrder(Long.parseLong(order.venueOrderId()), productId(order));
        return toResult(cancelled, order);
    }

    @Override
    public Optional<VenueResult> checkResting(Order order, Instrument instrument) {
        return resolve(order, instrument).filter(result -> !(result instanceof VenueResult.Resting));
    }

    /**
     * Where the order stands at Delta, found by its client order id; empty when Delta does not know it. Open orders
     * answer {@link VenueResult.Resting}. Used by {@link #checkResting} and by the reconciler for UNKNOWN orders.
     */
    public Optional<VenueResult> resolve(Order order, Instrument instrument) {
        return client.orderByClientId(order.clientOrderId()).map(delta -> toResult(delta, order));
    }

    private DeltaOrder pollUntilSettled(Order order, DeltaOrder placed) {
        Instant deadline = clock.instant().plus(POLL_TIMEOUT);
        DeltaOrder latest = placed;
        while (isLive(latest) && clock.instant().isBefore(deadline)) {
            sleeperMillis.accept(POLL_INTERVAL.toMillis());
            latest = client.orderByClientId(order.clientOrderId()).orElse(latest);
        }
        return latest;
    }

    /** The one place a Delta order state becomes a {@link VenueResult}. */
    private VenueResult toResult(DeltaOrder delta, Order order) {
        String venueOrderId = Long.toString(delta.id());
        if ("cancelled".equals(delta.state()) && delta.unfilledSize() < delta.size()) {
            log.error("Delta order {} (client id {}) was cancelled after a partial fill; not supported yet", delta.id(),
                    delta.clientOrderId());
            throw new DeltaApiException("Delta order cancelled after a partial fill", false, PARTIAL_FILL_UNSUPPORTED, true, 0, null);
        }
        return switch (delta.state()) {
            case "closed" -> filled(delta, order, venueOrderId);
            case "cancelled" -> order.timeInForce() == TimeInForce.IOC
                    ? new VenueResult.Expired(venueOrderId)
                    : new VenueResult.Cancelled(venueOrderId);
            default -> new VenueResult.Resting(venueOrderId);
        };
    }

    /**
     * Merges the Delta fills of one order into one {@link VenueResult.Filled}: size-weighted price, summed commission.
     * {@code venueFillId} is the Delta fill ids sorted and joined with "," (a single fill keeps its plain id), so it is a
     * stable idempotency key; consumers may split it on ",". Throws a retryable FILL_PENDING unless the visible fill
     * sizes add up to what Delta says is filled and to the order's remaining size.
     */
    private VenueResult filled(DeltaOrder delta, Order order, String venueOrderId) {
        Instant from = order.createdAt().minus(FILL_LOOKBACK);
        List<DeltaFill> fills = client.fills(from).stream().filter(f -> f.orderId() == delta.id()).toList();
        long filledAtDelta = delta.size() - delta.unfilledSize();
        long visible = fills.stream().mapToLong(DeltaFill::size).sum();
        if (fills.isEmpty() || visible != filledAtDelta || visible != order.size() - order.filledSize()) {
            throw new DeltaApiException("Delta order closed but its fills are not (all) visible yet", false, FILL_PENDING, true, 0, null);
        }
        if (fills.size() == 1) {
            DeltaFill fill = fills.get(0);
            return new VenueResult.Filled(venueOrderId, fill.id(), fill.price(), fill.commission(), role(fill));
        }
        BigDecimal notional = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        long size = 0;
        for (DeltaFill fill : fills) {
            notional = notional.add(fill.price().multiply(BigDecimal.valueOf(fill.size())));
            commission = commission.add(fill.commission());
            size += fill.size();
        }
        BigDecimal price = notional.divide(BigDecimal.valueOf(size), MathContext.DECIMAL128);
        String ids = String.join(",", fills.stream().map(DeltaFill::id).sorted().toList());
        return new VenueResult.Filled(venueOrderId, ids, price, commission, role(fills.get(0)));
    }

    private static String role(DeltaFill fill) {
        return fill.role().toUpperCase(Locale.ROOT);
    }

    private static boolean isLive(DeltaOrder delta) {
        return "open".equals(delta.state()) || "pending".equals(delta.state());
    }

    private static boolean isMarketOrIoc(Order order) {
        return order.type() == OrderType.MARKET || order.timeInForce() == TimeInForce.IOC;
    }

    private static DeltaPrivateClient.PlaceOrderBody body(Order order, Instrument instrument) {
        boolean market = order.type() == OrderType.MARKET;
        return new DeltaPrivateClient.PlaceOrderBody(
                instrument.venueProductId(),
                order.size(),
                order.side().name().toLowerCase(Locale.ROOT),
                market ? "market_order" : "limit_order",
                market ? null : order.limitPrice().toPlainString(),
                order.timeInForce().name().toLowerCase(Locale.ROOT),
                order.reduceOnly(),
                order.clientOrderId());
    }

    /** Cancel gets no instrument from the {@link TradingVenue} contract, so the product id comes from the instrument master. */
    private long productId(Order order) {
        return instruments.find(Venue.DELTA_TESTNET, order.symbol())
                .map(Instrument::venueProductId)
                .orElseThrow(() -> new IllegalStateException("instrument " + order.symbol() + " missing"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
