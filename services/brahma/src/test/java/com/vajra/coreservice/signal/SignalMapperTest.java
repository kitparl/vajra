package com.vajra.coreservice.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.vajra.contracts.common.v1.EventEnvelope;
import com.vajra.contracts.events.v1.OrderUpdated;
import com.vajra.contracts.events.v1.SignalGenerated;
import com.vajra.coreservice.oms.OrderIntent;
import com.vajra.coreservice.oms.OrderSource;
import com.vajra.coreservice.oms.OrderType;
import com.vajra.coreservice.oms.Side;
import com.vajra.coreservice.oms.TimeInForce;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SignalMapperTest {

    private static final String SIGNAL_ID = "3f2b8c1e-5a7d-4e9a-8b6c-1d2e3f4a5b6c";

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-28T10:00:00Z"), ZoneOffset.UTC);

    private final SignalMapper mapper = new SignalMapper(CLOCK);

    @Test
    void mapsHeartbeatSignal() {
        OrderIntent intent = mapper.toSignal(envelope(signalAt(CLOCK.instant()))).intent();

        assertThat(intent.idempotencyKey()).isEqualTo("signal-" + SIGNAL_ID).matches("[A-Za-z0-9_-]{1,64}");
        assertThat(intent.symbol()).isEqualTo("BTCUSD");
        assertThat(intent.side()).isEqualTo(Side.BUY);
        assertThat(intent.type()).isEqualTo(OrderType.MARKET);
        assertThat(intent.size()).isEqualTo(1);
        assertThat(intent.limitPrice()).isNull();
        assertThat(intent.timeInForce()).isEqualTo(TimeInForce.IOC);
        assertThat(intent.reduceOnly()).isFalse();
        assertThat(intent.source()).isEqualTo(OrderSource.SIGNAL);
        assertThat(intent.signalId()).isEqualTo(UUID.fromString(SIGNAL_ID));
    }

    @Test
    void rejectsGarbageBytes() {
        assertThatThrownBy(() -> mapper.toSignal("garbage".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidSignalException.class);
    }

    @Test
    void rejectsWrongPayloadType() {
        assertThatThrownBy(() -> mapper.toSignal(envelope(OrderUpdated.newBuilder().setOrderId(SIGNAL_ID).build())))
                .isInstanceOf(InvalidSignalException.class);
    }

    @Test
    void rejectsSizeZero() {
        assertInvalid(signal(SIGNAL_ID, "BTCUSD", "BUY", "MARKET", 0, CLOCK.instant()));
    }

    @Test
    void rejectsBadUuid() {
        assertInvalid(signal("not-a-uuid", "BTCUSD", "BUY", "MARKET", 1, CLOCK.instant()));
    }

    @Test
    void rejectsUnknownSide() {
        assertInvalid(signal(SIGNAL_ID, "BTCUSD", "HOLD", "MARKET", 1, CLOCK.instant()));
    }

    @Test
    void rejectsUnknownOrderType() {
        assertInvalid(signal(SIGNAL_ID, "BTCUSD", "BUY", "ICEBERG", 1, CLOCK.instant()));
    }

    @Test
    void missingGeneratedAtIsInvalid() {
        assertInvalid(signal(SIGNAL_ID, "BTCUSD", "BUY", "MARKET", 1, CLOCK.instant()).toBuilder().clearGeneratedAt().build());
    }

    @Test
    void futureGeneratedAtIsInvalid() {
        assertInvalid(signalAt(CLOCK.instant().plusSeconds(31)));
    }

    @Test
    void smallClockSkewIntoTheFutureIsAccepted() {
        assertThat(mapper.toSignal(envelope(signalAt(CLOCK.instant().plusSeconds(30)))).generatedAt())
                .isEqualTo(CLOCK.instant().plusSeconds(30));
    }

    private static SignalGenerated signalAt(Instant generatedAt) {
        return signal(SIGNAL_ID, "BTCUSD", "BUY", "MARKET", 1, generatedAt);
    }

    private void assertInvalid(SignalGenerated signal) {
        assertThatThrownBy(() -> mapper.toSignal(envelope(signal))).isInstanceOf(InvalidSignalException.class);
    }

    static SignalGenerated signal(String id, String symbol, String side, String orderType, long size) {
        return signal(id, symbol, side, orderType, size, Instant.now());
    }

    static SignalGenerated signal(String id, String symbol, String side, String orderType, long size,
            Instant generatedAt) {
        return SignalGenerated.newBuilder()
                .setGeneratedAt(Timestamp.newBuilder().setSeconds(generatedAt.getEpochSecond())
                        .setNanos(generatedAt.getNano())).setSignalId(id).setStrategy("heartbeat").setSymbol(symbol).setSide(side)
                .setOrderType(orderType).setSize(size).build();
    }

    static byte[] envelope(Message payload) {
        return EventEnvelope.newBuilder().setEventId(UUID.randomUUID().toString()).setEventType("signal.generated")
                .setProducer("signal-service").setPayload(Any.pack(payload)).build().toByteArray();
    }
}
