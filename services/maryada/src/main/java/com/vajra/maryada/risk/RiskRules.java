package com.vajra.maryada.risk;

import java.util.Set;

/** Deterministic, pure risk checks. No I/O, no clock, no randomness. */
public final class RiskRules {

    private static final Set<String> ALLOWED_VENUES = Set.of("PAPER", "DELTA_TESTNET");
    private static final Set<String> SOURCES = Set.of("MANUAL", "SIGNAL", "KILL_SWITCH");

    private RiskRules() {}

    public static Verdict evaluate(ApprovalRequest r, RiskLimits limits, boolean halted, long approvedLastMinute) {
        if (r.size() < 1 || r.side() == null || !(r.side().equals("BUY") || r.side().equals("SELL"))
                || r.source() == null || !SOURCES.contains(r.source())) {
            return Verdict.reject("INVALID_REQUEST");
        }
        if (r.venue() == null || !ALLOWED_VENUES.contains(r.venue())) {
            return Verdict.reject("VENUE_NOT_ALLOWED");
        }
        if (r.source().equals("KILL_SWITCH") && r.reduceOnly()) {
            return Verdict.approve();
        }
        if (halted) {
            return Verdict.reject("HALTED");
        }
        if (r.size() > limits.maxOrderSize()) {
            return Verdict.reject("ORDER_SIZE_LIMIT");
        }
        try {
            long after = r.side().equals("BUY")
                    ? Math.addExact(r.currentPosition(), r.size())
                    : Math.subtractExact(r.currentPosition(), r.size());
            long absAfter = Math.absExact(after);
            if (absAfter > limits.maxAbsPosition() && absAfter > Math.absExact(r.currentPosition())) {
                return Verdict.reject("POSITION_LIMIT");
            }
        } catch (ArithmeticException overflow) {
            return Verdict.reject("INVALID_REQUEST");
        }
        if (approvedLastMinute >= limits.maxOrdersPerMinute()) {
            return Verdict.reject("ORDER_RATE_LIMIT");
        }
        return Verdict.approve();
    }
}
