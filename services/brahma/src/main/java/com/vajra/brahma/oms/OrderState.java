package com.vajra.brahma.oms;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Lifecycle state of an order; transitions are enforced by {@link #canTransitionTo}. */
public enum OrderState {
    NEW,
    RISK_REJECTED,
    APPROVED,
    SENT,
    ACKED,
    PARTIALLY_FILLED,
    FILLED,
    REJECTED,
    UNKNOWN,
    CANCEL_REQUESTED,
    CANCELLED,
    EXPIRED;

    private static final Map<OrderState, Set<OrderState>> TRANSITIONS = new EnumMap<>(OrderState.class);

    static {
        TRANSITIONS.put(NEW, Set.of(RISK_REJECTED, APPROVED));
        TRANSITIONS.put(APPROVED, Set.of(SENT));
        TRANSITIONS.put(SENT, Set.of(ACKED, REJECTED, UNKNOWN));
        TRANSITIONS.put(ACKED, Set.of(PARTIALLY_FILLED, FILLED, CANCEL_REQUESTED, EXPIRED));
        TRANSITIONS.put(PARTIALLY_FILLED, Set.of(PARTIALLY_FILLED, FILLED, CANCEL_REQUESTED, EXPIRED));
        TRANSITIONS.put(CANCEL_REQUESTED, Set.of(CANCELLED, FILLED));
        TRANSITIONS.put(UNKNOWN, Set.of(ACKED, PARTIALLY_FILLED, FILLED, REJECTED, CANCELLED));
    }

    public boolean canTransitionTo(OrderState next) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean isTerminal() {
        return this == RISK_REJECTED || this == FILLED || this == REJECTED || this == CANCELLED || this == EXPIRED;
    }

    /** True while the order rests on the venue and can still fill. */
    public boolean isOpen() {
        return this == ACKED || this == PARTIALLY_FILLED;
    }
}
