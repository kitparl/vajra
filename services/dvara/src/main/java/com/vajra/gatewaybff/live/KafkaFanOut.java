package com.vajra.gatewaybff.live;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.vajra.contracts.common.v1.EventEnvelope;
import com.vajra.contracts.events.v1.FillCreated;
import com.vajra.contracts.events.v1.OrderUpdated;
import com.vajra.contracts.events.v1.PositionUpdated;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Forwards core's order, fill and position events to every open socket. Display only: it records nothing and never
 * retries; a message that cannot be decoded is logged and skipped. The group starts at the latest offset.
 */
@Component
class KafkaFanOut {

    private static final Logger log = LoggerFactory.getLogger(KafkaFanOut.class);

    private final LiveHub hub;
    private final LiveMessages messages;

    KafkaFanOut(LiveHub hub, LiveMessages messages) {
        this.hub = hub;
        this.messages = messages;
    }

    @KafkaListener(topics = {"order.updated", "fill.created", "position.updated"})
    void onMessage(ConsumerRecord<String, byte[]> record) {
        if (hub.count() == 0) {
            return;
        }
        try {
            hub.broadcast(toJson(record.topic(), EventEnvelope.parseFrom(record.value())));
        } catch (InvalidProtocolBufferException | RuntimeException e) {
            log.warn("Skipping undecodable record on {} at offset {}: {}", record.topic(), record.offset(),
                    e.getClass().getSimpleName());
        }
    }

    private String toJson(String topic, EventEnvelope envelope) throws InvalidProtocolBufferException {
        return switch (topic) {
            case "order.updated" -> messages.order(unpack(envelope, OrderUpdated.class));
            case "fill.created" -> messages.fill(unpack(envelope, FillCreated.class));
            case "position.updated" -> messages.position(unpack(envelope, PositionUpdated.class));
            default -> throw new IllegalArgumentException("unexpected topic");
        };
    }

    private static <T extends Message> T unpack(EventEnvelope envelope, Class<T> type)
            throws InvalidProtocolBufferException {
        return envelope.getPayload().unpack(type);
    }
}
