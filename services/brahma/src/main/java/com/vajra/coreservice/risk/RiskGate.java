package com.vajra.coreservice.risk;

import com.vajra.coreservice.oms.Order;

/** Pre-trade risk check; production asks risk-service. Must never throw for a bad answer: reject instead. */
public interface RiskGate {

    RiskDecision approve(Order order);
}
