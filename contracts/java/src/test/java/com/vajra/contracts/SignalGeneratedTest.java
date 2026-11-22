package com.vajra.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import com.vajra.contracts.common.v1.EventEnvelope;
import com.vajra.contracts.events.v1.SignalGenerated;
import org.junit.jupiter.api.Test;

class SignalGeneratedTest {

    @Test
    void roundTripsInsideEnvelope() throws Exception {
        SignalGenerated signal = SignalGenerated.newBuilder()
                .setSignalId("7c1f6a52-3c1e-4f0e-9a54-0d2f3b9f1a10")
                .setStrategy("heartbeat")
                .setSymbol("BTCUSD")
                .setSide("BUY")
                .setOrderType("MARKET")
                .setSize(1)
                .setGeneratedAt(Timestamp.newBuilder().setSeconds(1_700_000_000).build())
                .build();
        EventEnvelope envelope = EventEnvelope.newBuilder()
                .setEventType("signal.generated")
                .setPayload(Any.pack(signal))
                .build();

        EventEnvelope parsed = EventEnvelope.parseFrom(envelope.toByteArray());

        assertThat(parsed.getPayload().is(SignalGenerated.class)).isTrue();
        SignalGenerated unpacked = parsed.getPayload().unpack(SignalGenerated.class);
        assertThat(unpacked).isEqualTo(signal);
        assertThat(unpacked.getSignalId()).isEqualTo("7c1f6a52-3c1e-4f0e-9a54-0d2f3b9f1a10");
        assertThat(unpacked.getStrategy()).isEqualTo("heartbeat");
        assertThat(unpacked.getSymbol()).isEqualTo("BTCUSD");
        assertThat(unpacked.getSide()).isEqualTo("BUY");
        assertThat(unpacked.getOrderType()).isEqualTo("MARKET");
        assertThat(unpacked.getSize()).isEqualTo(1);
        assertThat(unpacked.getGeneratedAt().getSeconds()).isEqualTo(1_700_000_000);
    }
}
