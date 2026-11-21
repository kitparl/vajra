package com.vajra.brahma.oms;

import com.vajra.brahma.audit.AuditWriter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Every order write goes through here: the order row, its {@code order_transition} row and the {@code order.updated}
 * audit event are written together. Runs inside the caller's transaction (AuditWriter refuses otherwise).
 */
@Component
class OrderLifecycle {

    private final OrderRepository orders;
    private final AuditWriter audit;
    private final Clock clock;

    OrderLifecycle(OrderRepository orders, AuditWriter audit, Clock clock) {
        this.orders = orders;
        this.audit = audit;
        this.clock = clock;
    }

    /** Inserts a NEW order; throws {@link org.springframework.dao.DuplicateKeyException} for a used client order id. */
    void create(Order order, String requestHash) {
        orders.insert(order, requestHash);
        orders.addTransition(order.id(), new OrderTransition(null, OrderState.NEW, null, order.createdAt()));
        audit.record(OmsEvents.ORDER_UPDATED, OmsEvents.orderUpdated(order));
    }

    Order transition(Order o, OrderState to, String reason) {
        return move(o, to, reason, o.venueOrderId(), o.filledSize(), o.avgFillPrice(), o.fees());
    }

    /** APPROVED or RISK_REJECTED with risk-service's decision id (null when there was no decision). */
    Order riskDecided(Order o, OrderState to, String reason, UUID decisionId) {
        Order saved = transition(o, to, reason);
        if (decisionId != null) {
            orders.setRiskDecision(o.id(), decisionId);
        }
        return saved;
    }

    /** A venue answer that carries the venue's order id. */
    Order transition(Order o, OrderState to, String reason, String venueOrderId) {
        return move(o, to, reason, venueOrderId, o.filledSize(), o.avgFillPrice(), o.fees());
    }

    /** Records fill totals with the state change. */
    Order fill(Order o, OrderState to, long filledSize, BigDecimal avgFillPrice, BigDecimal fees) {
        return move(o, to, null, o.venueOrderId(), filledSize, avgFillPrice, fees);
    }

    /** Throws {@link org.springframework.dao.OptimisticLockingFailureException} when {@code o} is stale. */
    private Order move(Order o, OrderState to, String reason, String venueOrderId, long filledSize,
            BigDecimal avgFillPrice, BigDecimal fees) {
        if (!o.state().canTransitionTo(to)) {
            throw new IllegalStateException("order " + o.id() + ": illegal transition " + o.state() + " -> " + to);
        }
        Instant now = clock.instant();
        Order saved = orders.update(new Order(o.id(), o.clientOrderId(), o.idempotencyKey(), o.venue(),
                o.instrumentId(), o.symbol(), o.side(), o.type(), o.size(), o.limitPrice(), o.timeInForce(),
                o.reduceOnly(), to, filledSize, avgFillPrice, fees, venueOrderId, o.source(),
                reason == null ? o.reason() : reason, o.createdAt(), now, o.version()));
        orders.addTransition(o.id(), new OrderTransition(o.state(), to, reason, now));
        audit.record(OmsEvents.ORDER_UPDATED, OmsEvents.orderUpdated(saved));
        return saved;
    }
}
