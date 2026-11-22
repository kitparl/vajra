package com.vajra.coreservice.oms;

import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.connector.VenueResult;
import com.vajra.coreservice.connector.delta.DeltaApiException;
import com.vajra.coreservice.connector.delta.DeltaFill;
import com.vajra.coreservice.connector.delta.DeltaPosition;
import com.vajra.coreservice.connector.delta.DeltaPrivateClient;
import com.vajra.coreservice.connector.delta.DeltaTradingVenue;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.position.Position;
import com.vajra.coreservice.position.PositionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 60 s (first run 10 s after startup) brings our records in line with the venue. Four isolated steps: stuck
 * orders, UNKNOWN orders, fills Delta has and we lack, and position comparison. Our records are never overwritten:
 * a difference we cannot explain is an ERROR line with both values. Fills are recorded only through
 * {@link OrderService} and {@link FillRecorder}, per order, for orders we know. The Delta steps run only when the
 * Delta venue is active ({@code TRADING_MODE=DELTA_TESTNET}).
 */
@Component
public class Reconciler {

    /** {@code checked}: items examined; {@code resolved}: orders or fills brought in line; {@code mismatches}: ERROR lines. */
    public record ReconcileSummary(int checked, int resolved, int mismatches) {
        ReconcileSummary plus(ReconcileSummary other) {
            return new ReconcileSummary(checked + other.checked, resolved + other.resolved, mismatches + other.mismatches);
        }
    }

    static final Duration NEW_APPROVED_STUCK_AFTER = Duration.ofSeconds(60);
    static final Duration SENT_STUCK_AFTER = Duration.ofSeconds(30);
    static final Duration UNKNOWN_NOT_FOUND_AFTER = Duration.ofSeconds(60);
    static final Duration FILL_OVERLAP = Duration.ofMinutes(5);
    static final Duration FILL_FIRST_LOOKBACK = Duration.ofHours(24);
    static final String NOT_FOUND_AT_VENUE = "NOT_FOUND_AT_VENUE";
    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final OrderRepository orders;
    private final FillRepository fills;
    private final PositionRepository positions;
    private final InstrumentRepository instruments;
    private final OrderService orderService;
    private final Optional<DeltaTradingVenue> delta;
    private final DeltaPrivateClient client;
    private final Clock clock;

    Reconciler(OrderRepository orders, FillRepository fills, PositionRepository positions,
            InstrumentRepository instruments, OrderService orderService, Optional<DeltaTradingVenue> delta,
            DeltaPrivateClient client, Clock clock) {
        this.orders = orders;
        this.fills = fills;
        this.positions = positions;
        this.instruments = instruments;
        this.orderService = orderService;
        this.delta = delta;
        this.client = client;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT60S")
    public ReconcileSummary run() {
        ReconcileSummary total = isolated("stuck sweep", this::sweepStuck);
        total = total.plus(isolated("unknown orders", this::resolveUnknown));
        if (delta.isPresent()) {
            total = total.plus(isolated("missing fills", this::recordMissingFills));
            total = total.plus(isolated("positions", this::comparePositions));
        }
        log.info("Reconcile done: checked={} resolved={} mismatches={}", total.checked(), total.resolved(), total.mismatches());
        return total;
    }

    private static ReconcileSummary isolated(String step, Supplier<ReconcileSummary> body) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            log.error("Reconcile step '{}' failed; continuing with the next step", step, e);
            return new ReconcileSummary(0, 0, 0);
        }
    }

    /** Step 1, all venues. */
    private ReconcileSummary sweepStuck() {
        Instant now = clock.instant();
        int checked = 0;
        int resolved = 0;
        for (OrderState state : List.of(OrderState.NEW, OrderState.APPROVED)) {
            for (Order order : orders.findByStateBefore(state, now.minus(NEW_APPROVED_STUCK_AFTER))) {
                checked++;
                resolved += attempt(order, () -> orderService.closeStuckNotSent(order.id()));
            }
        }
        for (Order order : orders.findByStateBefore(OrderState.SENT, now.minus(SENT_STUCK_AFTER))) {
            checked++;
            resolved += attempt(order, () -> orderService.markStuckSent(order.id()));
        }
        return new ReconcileSummary(checked, resolved, 0);
    }

    /** Step 2. Paper has no venue to ask; Delta orders are looked up by client order id. */
    private ReconcileSummary resolveUnknown() {
        Instant now = clock.instant();
        int checked = 0;
        int resolved = 0;
        int mismatches = 0;
        for (Order order : orders.findAllByState(OrderState.UNKNOWN)) {
            checked++;
            if (order.venue() != Venue.DELTA_TESTNET || delta.isEmpty()) {
                log.error("UNKNOWN order {} on {} cannot be resolved: no venue to ask", order.id(), order.venue());
                mismatches++;
                continue;
            }
            try {
                Optional<VenueResult> answer = delta.get().resolve(order, instrumentOf(order));
                if (answer.isPresent()) {
                    orderService.resolveUnknown(order.id(), answer.get(), instrumentOf(order));
                    resolved++;
                } else if (!order.updatedAt().isAfter(now.minus(UNKNOWN_NOT_FOUND_AFTER))) {
                    resolved += orderService.rejectUnknown(order.id(), NOT_FOUND_AT_VENUE) ? 1 : 0;
                }
            } catch (RuntimeException e) {
                logFailure(order, e);
            }
        }
        return new ReconcileSummary(checked, resolved, mismatches);
    }

    /** Step 3: Delta fills we have no row for. Recorded per known open order via the same path as the checker. */
    private ReconcileSummary recordMissingFills() {
        Instant now = clock.instant();
        Instant from = fills.latestAt(Venue.DELTA_TESTNET).map(at -> at.minus(FILL_OVERLAP)).orElse(now.minus(FILL_FIRST_LOOKBACK));
        List<DeltaFill> missing = client.fills(from).stream()
                .filter(f -> !fills.containsVenueFillId(Venue.DELTA_TESTNET, f.id()))
                .toList();
        Map<Long, Order> toResolve = new LinkedHashMap<>();
        int mismatches = 0;
        for (DeltaFill fill : missing) {
            Optional<Order> known = orders.findByVenueOrderId(Venue.DELTA_TESTNET, Long.toString(fill.orderId()));
            if (known.isEmpty()) {
                log.error("Delta fill {} for unknown Delta order {} (manual trade?) not recorded", fill.id(), fill.orderId());
                mismatches++;
            } else if (known.get().state().isOpen() || known.get().state() == OrderState.CANCEL_REQUESTED) {
                toResolve.putIfAbsent(fill.orderId(), known.get());
            } else {
                log.error("Delta fill {} for order {} which is {} here, its fills are {}: not recorded", fill.id(),
                        known.get().id(), known.get().state(), joinedIds(known.get()));
                mismatches++;
            }
        }
        int resolved = 0;
        for (Order order : toResolve.values()) {
            try {
                Optional<VenueResult> answer = delta.get().resolve(order, instrumentOf(order));
                if (answer.isPresent() && !(answer.get() instanceof VenueResult.Resting)) {
                    orderService.applyResolved(order, answer.get(), instrumentOf(order));
                    resolved++;
                } else {
                    log.error("Delta has a fill for order {} that is still resting there (partial fill unsupported); not recorded",
                            order.id());
                    mismatches++;
                }
            } catch (RuntimeException e) {
                logFailure(order, e);
            }
        }
        return new ReconcileSummary(missing.size(), resolved, mismatches);
    }

    /** Step 4: compare only, never write. */
    private ReconcileSummary comparePositions() {
        int checked = 0;
        int mismatches = 0;
        for (Instrument instrument : instruments.findTradingEnabled()) {
            if (instrument.venue() != Venue.DELTA_TESTNET) {
                continue;
            }
            checked++;
            DeltaPosition theirs = client.position(instrument.venueProductId());
            Position ours = instruments.idOf(instrument.venue(), instrument.symbol())
                    .flatMap(id -> positions.find(Venue.DELTA_TESTNET, id))
                    .orElseGet(() -> Position.flat(Venue.DELTA_TESTNET, 0, instrument.symbol()));
            if (theirs.size() != ours.size()) {
                log.error("Position mismatch {}: ours size={}, Delta size={}", instrument.symbol(), ours.size(), theirs.size());
                mismatches++;
            } else if (theirs.size() != 0 && theirs.entryPrice() != null
                    && theirs.entryPrice().subtract(ours.avgEntryPrice()).abs().compareTo(instrument.tickSize()) > 0) {
                log.error("Position entry price mismatch {} (size {}): ours={}, Delta={}", instrument.symbol(), ours.size(),
                        ours.avgEntryPrice(), theirs.entryPrice());
                mismatches++;
            }
        }
        return new ReconcileSummary(checked, 0, mismatches);
    }

    private int attempt(Order order, Supplier<Boolean> change) {
        try {
            return change.get() ? 1 : 0;
        } catch (RuntimeException e) {
            log.error("Reconcile of order {} ({}) failed", order.id(), order.state(), e);
            return 0;
        }
    }

    /** A FILL_PENDING or other retryable Delta answer is normal and retried next run. */
    private static void logFailure(Order order, RuntimeException e) {
        if (e instanceof DeltaApiException d && d.retryable()) {
            log.warn("Reconcile of order {} deferred: {}", order.id(), d.code());
        } else {
            log.error("Reconcile of order {} failed", order.id(), e);
        }
    }

    private String joinedIds(Order order) {
        return fills.findByOrder(order.id()).stream().map(Fill::venueFillId).toList().toString();
    }

    private Instrument instrumentOf(Order order) {
        return instruments.find(OrderService.INSTRUMENT_VENUE, order.symbol())
                .orElseThrow(() -> new IllegalStateException("instrument " + order.symbol() + " missing"));
    }
}
