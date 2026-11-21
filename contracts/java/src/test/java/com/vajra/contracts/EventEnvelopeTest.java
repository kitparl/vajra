package com.vajra.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Any;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.vajra.contracts.common.v1.EventEnvelope;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    @Test
    void roundTripsThroughBytes() throws Exception {
        EventEnvelope original = EventEnvelope.newBuilder()
                .setEventId("e-1")
                .setEventType("test.ping")
                .setProducer("core-service")
                .setOccurredAt(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                .setPayload(Any.pack(StringValue.of("hi")))
                .build();

        EventEnvelope parsed = EventEnvelope.parseFrom(original.toByteArray());

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.getPayload().unpack(StringValue.class).getValue()).isEqualTo("hi");
    }
}
