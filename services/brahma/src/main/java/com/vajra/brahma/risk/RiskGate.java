package com.vajra.brahma.risk;

import com.vajra.brahma.oms.Order;

/** Pre-trade risk check. Slice 3 replaces the placeholder with a call to risk-service (maryada). */
public interface RiskGate {

    RiskDecision approve(Order order);
}
