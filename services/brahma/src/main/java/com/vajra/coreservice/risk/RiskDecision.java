package com.vajra.coreservice.risk;

import java.util.UUID;

/**
 * Outcome of a pre-trade risk check. {@code reason} is set when the order is rejected; {@code decisionId} is
 * risk-service's id for the decision, null when no decision came back (local halt, error, timeout).
 */
public record RiskDecision(boolean approved, String reason, UUID decisionId) {

    /** No usable answer from the risk check (error, timeout, bad response): the order is rejected. */
    public static final String RISK_CHECK_FAILED = "RISK_CHECK_FAILED";

    public static RiskDecision reject(String reason) {
        return new RiskDecision(false, reason, null);
    }
}
