package com.vajra.brahma.risk;

/** Outcome of a pre-trade risk check; {@code reason} is set when the order is rejected. */
public record RiskDecision(boolean approved, String reason) {

    public static RiskDecision approve() {
        return new RiskDecision(true, null);
    }

    public static RiskDecision reject(String reason) {
        return new RiskDecision(false, reason);
    }
}
