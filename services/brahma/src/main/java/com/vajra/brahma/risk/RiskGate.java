package com.vajra.brahma.risk;

import com.vajra.brahma.oms.Order;

/** Pre-trade risk check; production asks risk-service (maryada). Must never throw for a bad answer: reject instead. */
public interface RiskGate {

    RiskDecision approve(Order order);
}
