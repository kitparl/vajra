package com.vajra.coreservice.oms;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderIntentTest {

    /** Hashes of orders without a signal are stored in the database: they must never change. */
    @Test
    void manualOrderHashIsUnchanged() {
        OrderIntent manual = new OrderIntent("k", "BTCUSD", Side.BUY, OrderType.MARKET, 1, null, TimeInForce.IOC, false,
                OrderSource.MANUAL, null);
        OrderIntent kill = new OrderIntent("k", "BTCUSD", Side.SELL, OrderType.LIMIT, 2, new BigDecimal("50000.0"),
                TimeInForce.GTC, true, OrderSource.KILL_SWITCH, null);

        assertThat(manual.requestHash()).isEqualTo("46c74267cae4f76da0442f5f2fa78372b9966f251540ac9af473e8c0ba9a3de1");
        assertThat(kill.requestHash()).isEqualTo("c7f06d17f830c61fb192b0da783f0eb9edc7121faa3f2d50c3993fa3ee5d022f");
    }

    @Test
    void signalIdChangesTheHash() {
        OrderIntent without = new OrderIntent("k", "BTCUSD", Side.BUY, OrderType.MARKET, 1, null, TimeInForce.IOC,
                false, OrderSource.SIGNAL, null);
        OrderIntent with = new OrderIntent("k", "BTCUSD", Side.BUY, OrderType.MARKET, 1, null, TimeInForce.IOC, false,
                OrderSource.SIGNAL, UUID.randomUUID());

        assertThat(with.requestHash()).isNotEqualTo(without.requestHash());
    }
}
