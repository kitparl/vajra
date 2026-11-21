package com.vajra.brahma.risk;

import com.vajra.brahma.connector.Venue;
import com.vajra.brahma.oms.Order;
import org.springframework.stereotype.Component;

/**
 * Placeholder until risk-service exists (slice 3): paper orders pass, anything that could reach a real venue is
 * rejected (fail-closed, CLAUDE.md rule 3).
 */
@Component
public class PaperOnlyRiskGate implements RiskGate {

    static final String NO_RISK_SERVICE = "NO_RISK_SERVICE";

    @Override
    public RiskDecision approve(Order order) {
        return order.venue() == Venue.PAPER ? RiskDecision.approve() : RiskDecision.reject(NO_RISK_SERVICE);
    }
}
