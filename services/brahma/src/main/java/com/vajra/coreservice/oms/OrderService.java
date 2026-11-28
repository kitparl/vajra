package com.vajra.coreservice.oms;

import com.vajra.coreservice.api.ApiException;
import com.vajra.coreservice.connector.TradingVenue;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.connector.VenueResult;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.position.Position;
import com.vajra.coreservice.position.PositionMath;
import com.vajra.coreservice.position.PositionRepository;
import com.vajra.coreservice.risk.RiskDecision;
import com.vajra.coreservice.risk.RiskGate;
import com.vajra.coreservice.risk.TradingStateRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Order orchestration (spec §7.6). Each step commits on its own, so the NEW row and its audit survive a failure in a
 * later step; the venue is called outside any transaction. A venue error leaves the order UNKNOWN: it is never re-sent.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** Paper and testnet orders both trade the testnet instrument master and its quotes. */
    static final Venue INSTRUMENT_VENUE = Venue.DELTA_TESTNET;
    static final String REDUCE_ONLY_WOULD_INCREASE = "REDUCE_ONLY_WOULD_INCREASE";
    static final String NO_TRADING_VENUE = "NO_TRADING_VENUE";
    static final String HALTED = "HALTED";
    static final String VENUE_ERROR = "VENUE_ERROR";
    static final String FILL_NOT_RECORDED = "FILL_NOT_RECORDED";
    static final String STUCK_NOT_SENT = "STUCK_NOT_SENT";
    static final String STUCK_SENT = "STUCK_SENT";
    public static final String STALE_SIGNAL = "STALE_SIGNAL";
    static final String RECONCILED = "RECONCILED";
    private static final int LIST_LIMIT = 100;

    private final TransactionTemplate tx;
    private final OrderRepository orders;
    private final FillRepository fills;
    private final PositionRepository positions;
    private final InstrumentRepository instruments;
    private final RiskGate riskGate;
    private final TradingStateRepository tradingState;
    private final List<TradingVenue> venues;
    private final OrderLifecycle lifecycle;
    private final FillRecorder fillRecorder;
    private final TradingProperties trading;
    private final Clock clock;

    OrderService(TransactionTemplate tx, OrderRepository orders, FillRepository fills, PositionRepository positions,
            InstrumentRepository instruments, RiskGate riskGate, TradingStateRepository tradingState,
            List<TradingVenue> venues, OrderLifecycle lifecycle, FillRecorder fillRecorder, TradingProperties trading,
            Clock clock) {
        this.tx = tx;
        this.orders = orders;
        this.fills = fills;
        this.positions = positions;
        this.instruments = instruments;
        this.riskGate = riskGate;
        this.tradingState = tradingState;
        this.venues = List.copyOf(venues);
        this.lifecycle = lifecycle;
        this.fillRecorder = fillRecorder;
        this.trading = trading;
        this.clock = clock;
    }

    /** Places an order once per idempotency key; a replay with the same body returns the stored order. */
    public PlaceOrderResult place(OrderIntent raw) {
        return createOnce(raw, this::process);
    }

    /**
     * Records an order that must not trade: created idempotently like {@link #place}, then NEW to RISK_REJECTED with
     * the reason and no risk decision (as in {@link #closeStuckNotSent}). Risk and the venue are never called.
     */
    public PlaceOrderResult rejectWithoutRisk(OrderIntent raw, String reason) {
        return createOnce(raw,
                (order, instrument) -> inTx(() -> lifecycle.riskDecided(order, OrderState.RISK_REJECTED, reason, null)));
    }

    /** Validates and creates the NEW order once per key; {@code next} runs only for a newly created order. */
    private PlaceOrderResult createOnce(OrderIntent raw, BiFunction<Order, Instrument, Order> next) {
        OrderIntent intent = OrderValidation.normalize(raw);
        Instrument instrument = instruments.find(INSTRUMENT_VENUE, intent.symbol())
                .orElseThrow(() -> ApiException.badRequest("unknown symbol: " + intent.symbol()));
        OrderValidation.checkInstrument(intent, instrument);
        long instrumentId = instruments.idOf(INSTRUMENT_VENUE, intent.symbol()).orElseThrow();

        String requestHash = intent.requestHash();
        Order order = newOrder(intent, instrumentId);
        try {
            tx.executeWithoutResult(status -> lifecycle.create(order, requestHash, intent.signalId()));
        } catch (DuplicateKeyException e) {
            return existing(intent, order.clientOrderId(), requestHash);
        }
        return new PlaceOrderResult(next.apply(order, instrument), true);
    }

    /**
     * Cancels a resting order. A CANCEL_REQUESTED order (an earlier venue cancel failed) gets its venue cancel
     * re-sent; the kill switch and the open-order checker rely on this.
     */
    public Order cancel(UUID orderId) {
        Order order = orders.find(orderId).orElseThrow(() -> ApiException.notFound("order not found: " + orderId));
        if (!order.state().isOpen() && order.state() != OrderState.CANCEL_REQUESTED) {
            throw ApiException.conflict("order is " + order.state() + " and cannot be cancelled");
        }
        TradingVenue venue = venueFor(order.venue())
                .orElseThrow(() -> new IllegalStateException("no trading venue for open order " + orderId));
        Order requested = order.state() == OrderState.CANCEL_REQUESTED
                ? order
                : inTxOrConflict(() -> lifecycle.transition(order, OrderState.CANCEL_REQUESTED, null));
        return sendCancel(requested, venue);
    }

    /** Sends the venue cancel; CANCELLED only when the venue confirms, otherwise the order stays CANCEL_REQUESTED. */
    private Order sendCancel(Order requested, TradingVenue venue) {
        VenueResult result;
        try {
            result = venue.cancel(requested);
        } catch (RuntimeException e) {
            log.error("Cancel of order {} failed at the venue; left CANCEL_REQUESTED", requested.id(), e);
            return requested;
        }
        if (!(result instanceof VenueResult.Cancelled)) {
            log.warn("Cancel of order {} answered {}; left CANCEL_REQUESTED", requested.id(), result);
            return requested;
        }
        return inTxOrConflict(() -> lifecycle.transition(requested, OrderState.CANCELLED, null));
    }

    /** Asks the venue how a resting order stands and applies the answer; used by {@link OpenOrderChecker}. */
    public void onRestingCheck(Order order) {
        Optional<TradingVenue> venue = venueFor(order.venue());
        if (venue.isEmpty()) {
            log.warn("No trading venue for resting order {} on {}", order.id(), order.venue());
            return;
        }
        Instrument instrument = instruments.find(INSTRUMENT_VENUE, order.symbol())
                .orElseThrow(() -> new IllegalStateException("instrument " + order.symbol() + " missing"));
        Optional<VenueResult> result = venue.get().checkResting(order, instrument);
        if (result.isPresent()) {
            applyVenueResult(order, result.get(), instrument);
        } else if (order.state() == OrderState.CANCEL_REQUESTED && order.venue() != Venue.PAPER) {
            // Nothing final at the venue, so a cancel we asked for may never have arrived: ask again.
            log.warn("Order {} is CANCEL_REQUESTED but still live at {}; re-sending the cancel", order.id(), order.venue());
            sendCancel(order, venue.get());
        }
    }

    private void applyVenueResult(Order order, VenueResult result, Instrument instrument) {
        switch (result) {
            case VenueResult.Filled filled -> fillRecorder.record(order.id(), filled, instrument);
            case VenueResult.Cancelled cancelled -> inTx(() -> closeAtVenue(order.id(), OrderState.CANCELLED));
            case VenueResult.Expired expired -> inTx(() -> closeAtVenue(order.id(), OrderState.EXPIRED));
            default -> log.warn("Venue answer for order {} was {}; ignored", order.id(), result);
        }
    }

    /**
     * Reconciler: closes an order that was never sent. NEW becomes RISK_REJECTED (no risk decision), APPROVED goes
     * through SENT to REJECTED exactly like {@link #rejectWithoutSending}. Returns false when the order moved on.
     */
    boolean closeStuckNotSent(UUID orderId) {
        return inTx(() -> {
            Order current = orders.find(orderId).orElseThrow();
            switch (current.state()) {
                case NEW -> lifecycle.riskDecided(current, OrderState.RISK_REJECTED, STUCK_NOT_SENT, null);
                case APPROVED -> lifecycle.transition(
                        lifecycle.transition(current, OrderState.SENT, null), OrderState.REJECTED, STUCK_NOT_SENT);
                default -> {
                    return false;
                }
            }
            return true;
        });
    }

    /** Reconciler: a SENT order with no answer becomes UNKNOWN. Returns false when the order moved on. */
    boolean markStuckSent(UUID orderId) {
        return moveFrom(orderId, OrderState.SENT, OrderState.UNKNOWN, STUCK_SENT);
    }

    /** Reconciler: an UNKNOWN order the venue does not know is REJECTED. Returns false when the order moved on. */
    boolean rejectUnknown(UUID orderId, String reason) {
        return moveFrom(orderId, OrderState.UNKNOWN, OrderState.REJECTED, reason);
    }

    /**
     * Reconciler: applies what the venue says about an UNKNOWN order. The order first becomes ACKED with the venue
     * order id (an allowed UNKNOWN transition), then the answer is applied like a resting check, so a fill goes
     * through {@link FillRecorder}. A failure after the ACK leaves an ACKED order that the open-order checker resolves.
     */
    void resolveUnknown(UUID orderId, VenueResult result, Instrument instrument) {
        String venueOrderId = switch (result) {
            case VenueResult.Filled filled -> filled.venueOrderId();
            case VenueResult.Resting resting -> resting.venueOrderId();
            case VenueResult.Cancelled cancelled -> cancelled.venueOrderId();
            case VenueResult.Expired expired -> expired.venueOrderId();
            case VenueResult.Rejected rejected -> null;
        };
        if (venueOrderId == null) {
            log.warn("Venue answered {} for UNKNOWN order {}; ignored", result, orderId);
            return;
        }
        Order acked = inTx(() -> {
            Order current = orders.find(orderId).orElseThrow();
            return current.state() == OrderState.UNKNOWN
                    ? lifecycle.transition(current, OrderState.ACKED, RECONCILED, venueOrderId)
                    : null;
        });
        if (acked != null && !(result instanceof VenueResult.Resting)) {
            applyVenueResult(acked, result, instrument);
        }
    }

    /** Reconciler: applies the venue's answer for an order that is still open here (a fill we have not recorded). */
    void applyResolved(Order order, VenueResult result, Instrument instrument) {
        applyVenueResult(order, result, instrument);
    }

    private boolean moveFrom(UUID orderId, OrderState from, OrderState to, String reason) {
        return inTx(() -> {
            Order current = orders.find(orderId).orElseThrow();
            if (current.state() != from) {
                return false;
            }
            lifecycle.transition(current, to, reason);
            return true;
        });
    }

    /** The venue ended the order (cancelled or expired). Re-reads the order; a state that moved on is left alone. */
    private Order closeAtVenue(UUID orderId, OrderState target) {
        Order current = orders.find(orderId).orElseThrow();
        if (target == OrderState.CANCELLED && current.state().isOpen()) {
            current = lifecycle.transition(current, OrderState.CANCEL_REQUESTED, null);
        }
        if (current.state().canTransitionTo(target)) {
            return lifecycle.transition(current, target, null);
        }
        log.info("Order {} is {}; venue {} ignored", orderId, current.state(), target);
        return current;
    }

    public Optional<OrderDetails> details(UUID orderId) {
        return orders.find(orderId)
                .map(o -> new OrderDetails(o, orders.riskDecisionId(orderId).orElse(null), orders.signalId(orderId).orElse(null),
                        orders.transitions(orderId), fills.findByOrder(orderId)));
    }

    /** Newest first, at most 100. */
    public List<Order> list(Optional<OrderState> state) {
        return orders.findByState(state, LIST_LIMIT);
    }

    private PlaceOrderResult existing(OrderIntent intent, String clientOrderId, String requestHash) {
        Order existing = orders.findByClientOrderId(clientOrderId)
                .orElseThrow(() -> new IllegalStateException("duplicate client order id " + clientOrderId + " not found"));
        boolean sameRequest = existing.idempotencyKey().equals(intent.idempotencyKey())
                && orders.requestHash(existing.id()).map(requestHash::equals).orElse(false);
        if (!sameRequest) {
            throw ApiException.conflict("Idempotency-Key already used for a different order");
        }
        return new PlaceOrderResult(existing, false);
    }

    /** Steps 3-5: risk, reduce-only, venue. */
    private Order process(Order order, Instrument instrument) {
        RiskDecision decision = riskCheck(order);
        if (!decision.approved()) {
            return inTx(() -> lifecycle.riskDecided(
                    order, OrderState.RISK_REJECTED, decision.reason(), decision.decisionId()));
        }
        Order approved = inTx(() -> lifecycle.riskDecided(order, OrderState.APPROVED, null, decision.decisionId()));
        if (approved.reduceOnly() && wouldIncrease(approved)) {
            return rejectWithoutSending(approved, REDUCE_ONLY_WOULD_INCREASE);
        }
        Optional<TradingVenue> venue = venueFor(approved.venue());
        if (venue.isEmpty()) {
            return rejectWithoutSending(approved, NO_TRADING_VENUE);
        }
        Order sent = inTx(() -> moveToSent(approved));
        if (sent.state() == OrderState.REJECTED) {
            return sent;
        }
        VenueResult result;
        try {
            result = venue.get().place(sent, instrument);
        } catch (RuntimeException e) {
            log.error("Venue call for order {} failed; marked UNKNOWN (not re-sent)", sent.id(), e);
            return inTx(() -> lifecycle.transition(sent, OrderState.UNKNOWN, VENUE_ERROR));
        }
        return onPlaced(sent, result, instrument);
    }

    /**
     * APPROVED to SENT, re-reading the halt in the same transaction: an order approved just before a kill must not
     * reach the venue. A halted non-flatten order goes SENT to REJECTED (HALTED) without a venue call.
     */
    private Order moveToSent(Order approved) {
        Order sent = lifecycle.transition(approved, OrderState.SENT, null);
        if (tradingState.halted() && !isKillSwitchFlatten(approved)) {
            return lifecycle.transition(sent, OrderState.REJECTED, HALTED);
        }
        return sent;
    }

    private Order onPlaced(Order sent, VenueResult result, Instrument instrument) {
        return switch (result) {
            case VenueResult.Filled filled -> recordPlacementFill(sent, filled, instrument);
            case VenueResult.Resting resting ->
                inTx(() -> lifecycle.transition(sent, OrderState.ACKED, null, resting.venueOrderId()));
            case VenueResult.Expired expired -> inTx(() -> lifecycle.transition(
                    lifecycle.transition(sent, OrderState.ACKED, null, expired.venueOrderId()), OrderState.EXPIRED, null));
            case VenueResult.Rejected rejected ->
                inTx(() -> lifecycle.transition(sent, OrderState.REJECTED, rejected.reason()));
            case VenueResult.Cancelled cancelled -> markUnknown(sent.id(), "UNEXPECTED_VENUE_RESULT");
        };
    }

    /** The venue filled the order but we could not record it: UNKNOWN, for reconciliation (never re-sent). */
    private Order recordPlacementFill(Order sent, VenueResult.Filled filled, Instrument instrument) {
        try {
            return fillRecorder.record(sent.id(), filled, instrument);
        } catch (RuntimeException e) {
            log.error("Fill {} for order {} could not be recorded", filled.venueFillId(), sent.id(), e);
            return markUnknown(sent.id(), FILL_NOT_RECORDED);
        }
    }

    private Order markUnknown(UUID orderId, String reason) {
        return inTx(() -> {
            Order current = orders.find(orderId).orElseThrow();
            return current.state() == OrderState.SENT
                    ? lifecycle.transition(current, OrderState.UNKNOWN, reason)
                    : current;
        });
    }

    private Order rejectWithoutSending(Order approved, String reason) {
        return inTx(() -> lifecycle.transition(
                lifecycle.transition(approved, OrderState.SENT, null), OrderState.REJECTED, reason));
    }

    /**
     * Fail-closed: an error in the risk check rejects the order. While halted locally, only kill-switch reduce-only
     * flattens reach risk-service, so a halt holds even when risk-service is down.
     */
    private RiskDecision riskCheck(Order order) {
        try {
            if (tradingState.halted() && !isKillSwitchFlatten(order)) {
                return RiskDecision.reject(HALTED);
            }
            RiskDecision decision = riskGate.approve(order);
            return decision == null ? RiskDecision.reject(RiskDecision.RISK_CHECK_FAILED) : decision;
        } catch (RuntimeException e) {
            log.error("Risk check for order {} failed; rejecting", order.id(), e);
            return RiskDecision.reject(RiskDecision.RISK_CHECK_FAILED);
        }
    }

    private static boolean isKillSwitchFlatten(Order order) {
        return isKillSwitchFlatten(order.source(), order.reduceOnly());
    }

    /** Kill flattens are exempt from the halt and from an instrument's {@code tradingEnabled}. */
    static boolean isKillSwitchFlatten(OrderSource source, boolean reduceOnly) {
        return source == OrderSource.KILL_SWITCH && reduceOnly;
    }

    /** True when filling the order would grow the position or flip its side. */
    private boolean wouldIncrease(Order order) {
        long current = positions.find(order.venue(), order.instrumentId()).map(Position::size).orElse(0L);
        return PositionMath.increasesOrFlips(current, order.side(), order.size());
    }

    /**
     * Looked up per call rather than mapped once at construction: venues are few, and a lazily resolved venue also
     * works with test doubles whose {@code venue()} is stubbed after the context starts.
     */
    private Optional<TradingVenue> venueFor(Venue venue) {
        return venues.stream().filter(v -> v.venue() == venue).findFirst();
    }

    private Order newOrder(OrderIntent i, long instrumentId) {
        Instant now = clock.instant();
        return new Order(UUID.randomUUID(), ClientOrderIds.fromKey(i.idempotencyKey()), i.idempotencyKey(),
                i.venue() != null ? i.venue() : trading.mode().venue(), instrumentId, i.symbol(), i.side(), i.type(), i.size(), i.limitPrice(),
                i.timeInForce(), i.reduceOnly(), OrderState.NEW, 0, null, BigDecimal.ZERO, null, i.source(), null, now,
                now, 0);
    }

    private <T> T inTx(Supplier<T> step) {
        return tx.execute(status -> step.get());
    }

    private Order inTxOrConflict(Supplier<Order> step) {
        try {
            return inTx(step);
        } catch (OptimisticLockingFailureException e) {
            throw ApiException.retryableConflict("order changed, retry");
        }
    }
}
