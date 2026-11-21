package com.vajra.brahma.oms;

import com.vajra.brahma.audit.AuditWriter;
import com.vajra.brahma.connector.VenueResult;
import com.vajra.brahma.instrument.Instrument;
import com.vajra.brahma.position.Position;
import com.vajra.brahma.position.PositionMath;
import com.vajra.brahma.position.PositionRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies a venue fill: fill row, order update, position update and their audit events in ONE transaction (spec
 * §7.6 step 5). A version conflict (e.g. a market fill and a matcher fill racing on the same position) retries with
 * freshly read order and position, up to {@value #MAX_ATTEMPTS} attempts.
 */
@Component
class FillRecorder {

    private static final Logger log = LoggerFactory.getLogger(FillRecorder.class);
    static final int MAX_ATTEMPTS = 3;

    private final TransactionTemplate tx;
    private final OrderRepository orders;
    private final FillRepository fills;
    private final PositionRepository positions;
    private final OrderLifecycle lifecycle;
    private final AuditWriter audit;
    private final Clock clock;

    FillRecorder(TransactionTemplate tx, OrderRepository orders, FillRepository fills, PositionRepository positions,
            OrderLifecycle lifecycle, AuditWriter audit, Clock clock) {
        this.tx = tx;
        this.orders = orders;
        this.fills = fills;
        this.positions = positions;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Fills the rest of the order. A SENT order is acked first (same transaction). Returns the order unchanged when
     * it can no longer fill or the fill was already recorded, so a fill is never applied twice. Throws
     * {@link OptimisticLockingFailureException} when every attempt conflicted.
     */
    Order record(UUID orderId, VenueResult.Filled filled, Instrument instrument) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> recordOnce(orderId, filled, instrument));
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.info("Fill for order {} conflicted (attempt {}), retrying: {}", orderId, attempt, e.getMessage());
            }
        }
    }

    private Order recordOnce(UUID orderId, VenueResult.Filled filled, Instrument instrument) {
        Order found = orders.find(orderId).orElseThrow(() -> new IllegalStateException("order " + orderId + " not found"));
        Order order = found.state() == OrderState.SENT
                ? lifecycle.transition(found, OrderState.ACKED, null, filled.venueOrderId())
                : found;
        if (!isFillable(order.state())) {
            log.warn("Fill {} ignored: order {} is {}", filled.venueFillId(), orderId, order.state());
            return order;
        }
        long size = order.size() - order.filledSize();
        Instant now = clock.instant();
        Fill fill = new Fill(UUID.randomUUID(), order.id(), order.venue(), filled.venueFillId(), filled.price(), size,
                filled.fee(), filled.liquidity(), now);
        if (!fills.insertIfNew(fill)) {
            log.warn("Fill {} for order {} already recorded", filled.venueFillId(), orderId);
            return order;
        }
        Order updated = lifecycle.fill(order, OrderState.FILLED, order.size(), averagePrice(order, fill),
                order.fees().add(fill.fee()));
        audit.record(OmsEvents.FILL_CREATED, OmsEvents.fillCreated(fill, updated));
        Position current = positions.find(order.venue(), order.instrumentId())
                .orElseGet(() -> Position.flat(order.venue(), order.instrumentId(), order.symbol()));
        Position saved = positions.save(PositionMath.applyFill(current, order.side(), size, fill.price(),
                instrument.contractValue(), fill.fee(), now));
        audit.record(OmsEvents.POSITION_UPDATED, OmsEvents.positionUpdated(saved));
        return updated;
    }

    private static boolean isFillable(OrderState state) {
        return state.isOpen() || state == OrderState.CANCEL_REQUESTED;
    }

    private static BigDecimal averagePrice(Order order, Fill fill) {
        if (order.filledSize() == 0) {
            return fill.price();
        }
        return order.avgFillPrice().multiply(BigDecimal.valueOf(order.filledSize()))
                .add(fill.price().multiply(BigDecimal.valueOf(fill.size())))
                .divide(BigDecimal.valueOf(order.filledSize() + fill.size()), MathContext.DECIMAL128);
    }
}
