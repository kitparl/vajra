package com.vajra.coreservice.oms;

import java.util.List;
import java.util.UUID;

/**
 * An order with its full history; {@code riskDecisionId} is null when risk-service gave no decision, {@code signalId}
 * when the order did not come from a signal.
 */
public record OrderDetails(
        Order order, UUID riskDecisionId, UUID signalId, List<OrderTransition> transitions, List<Fill> fills) {}
