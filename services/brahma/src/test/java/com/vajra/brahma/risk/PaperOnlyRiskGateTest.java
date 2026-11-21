package com.vajra.brahma.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.vajra.brahma.connector.Venue;
import com.vajra.brahma.oms.Order;
import com.vajra.brahma.oms.OrderSource;
import com.vajra.brahma.oms.OrderState;
import com.vajra.brahma.oms.OrderType;
import com.vajra.brahma.oms.Side;
import com.vajra.brahma.oms.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaperOnlyRiskGateTest {

    private final PaperOnlyRiskGate gate = new PaperOnlyRiskGate();

    @Test
    void paperOrderIsApproved() {
        RiskDecision decision = gate.approve(order(Venue.PAPER));

        assertThat(decision.approved()).isTrue();
        assertThat(decision.reason()).isNull();
    }

    @Test
    void deltaTestnetOrderIsRejectedWithoutRiskService() {
        RiskDecision decision = gate.approve(order(Venue.DELTA_TESTNET));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason()).isEqualTo("NO_RISK_SERVICE");
    }

    private static Order order(Venue venue) {
        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        return new Order(UUID.randomUUID(), "cid", "key", venue, 1L, "BTCUSD", Side.BUY, OrderType.MARKET, 1L, null,
                TimeInForce.IOC, false, OrderState.NEW, 0L, null, BigDecimal.ZERO, null, OrderSource.MANUAL, null, now,
                now, 0L);
    }
}
