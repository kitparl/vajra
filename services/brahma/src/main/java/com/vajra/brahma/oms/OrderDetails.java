package com.vajra.brahma.oms;

import java.util.List;
import java.util.UUID;

/** An order with its full history; {@code riskDecisionId} is null when risk-service gave no decision. */
public record OrderDetails(Order order, UUID riskDecisionId, List<OrderTransition> transitions, List<Fill> fills) {}
