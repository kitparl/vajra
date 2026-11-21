package com.vajra.brahma.testsupport;

import com.vajra.brahma.oms.Order;
import com.vajra.brahma.risk.RiskDecision;
import com.vajra.brahma.risk.RiskGate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Stands in for maryada in Spring tests: approves every order with a fixed decision id and counts the calls. */
public class ApprovingRiskGate implements RiskGate {

    public static final UUID DECISION_ID = UUID.fromString("0b7e4a3c-2d6f-4f0e-9c1a-5e8d7b6a4c21");

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public RiskDecision approve(Order order) {
        calls.incrementAndGet();
        return new RiskDecision(true, null, DECISION_ID);
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        calls.set(0);
    }
}
