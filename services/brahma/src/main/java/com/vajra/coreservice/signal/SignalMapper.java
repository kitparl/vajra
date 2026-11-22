package com.vajra.coreservice.signal;

import com.google.protobuf.InvalidProtocolBufferException;
import com.vajra.contracts.common.v1.EventEnvelope;
import com.vajra.contracts.events.v1.SignalGenerated;
import com.vajra.coreservice.oms.OrderIntent;
import com.vajra.coreservice.oms.OrderSource;
import com.vajra.coreservice.oms.OrderType;
import com.vajra.coreservice.oms.Side;
import com.vajra.coreservice.oms.TimeInForce;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Turns a {@code signal.generated} envelope into an order intent. The key is derived from the signal id. */
@Component
class SignalMapper {

    static final String KEY_PREFIX = "signal-";

    OrderIntent toIntent(byte[] envelopeBytes) {
        SignalGenerated signal = unpack(envelopeBytes);
        UUID signalId = parseUuid(signal.getSignalId());
        if (signal.getSize() < 1) {
            throw new InvalidSignalException("size must be at least 1: " + signal.getSize());
        }
        if (!OrderType.MARKET.name().equals(signal.getOrderType())) {
            throw new InvalidSignalException("unsupported order_type: " + signal.getOrderType());
        }
        return new OrderIntent(KEY_PREFIX + signalId, signal.getSymbol(), parseSide(signal.getSide()), OrderType.MARKET,
                signal.getSize(), null, TimeInForce.IOC, false, OrderSource.SIGNAL, signalId);
    }

    /** The signal id for log lines, or {@code "unknown"} when the message cannot be read. */
    static String signalIdOf(byte[] envelopeBytes) {
        try {
            return unpack(envelopeBytes).getSignalId();
        } catch (InvalidSignalException e) {
            return "unknown";
        }
    }

    private static SignalGenerated unpack(byte[] envelopeBytes) {
        try {
            EventEnvelope envelope = EventEnvelope.parseFrom(envelopeBytes);
            if (!envelope.getPayload().is(SignalGenerated.class)) {
                throw new InvalidSignalException("payload is not SignalGenerated: " + envelope.getPayload().getTypeUrl());
            }
            return envelope.getPayload().unpack(SignalGenerated.class);
        } catch (InvalidProtocolBufferException e) {
            throw new InvalidSignalException("message is not a valid envelope", e);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidSignalException("signal_id is not a UUID: " + value, e);
        }
    }

    private static Side parseSide(String value) {
        try {
            return Side.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidSignalException("unknown side: " + value, e);
        }
    }
}
