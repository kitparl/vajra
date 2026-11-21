package com.vajra.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Any;
import com.vajra.contracts.common.v1.EventEnvelope;
import com.vajra.contracts.events.v1.OrderUpdated;
import org.junit.jupiter.api.Test;

class OrderEventsTest {

    @Test
    void orderUpdatedRoundTripsInEnvelope() throws Exception {
        OrderUpdated order = OrderUpdated.newBuilder()
                .setOrderId("o-1")
                .setState("FILLED")
                .setAvgFillPrice("57878.5")
                .build();
        EventEnvelope envelope = EventEnvelope.newBuilder()
                .setEventType("order.updated")
                .setPayload(Any.pack(order))
                .build();

        EventEnvelope parsed = EventEnvelope.parseFrom(envelope.toByteArray());
        OrderUpdated unpacked = parsed.getPayload().unpack(OrderUpdated.class);

        assertThat(unpacked).isEqualTo(order);
        assertThat(unpacked.getAvgFillPrice()).isEqualTo("57878.5");
    }
}
