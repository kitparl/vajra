package com.vajra.coreservice.signal;

import com.vajra.coreservice.api.ApiException;
import com.vajra.coreservice.oms.Order;
import com.vajra.coreservice.oms.OrderService;
import com.vajra.coreservice.oms.PlaceOrderResult;
import java.time.Clock;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code signal.generated} and places one order per signal. Offsets are acknowledged only once the message is
 * handled or can never succeed (bad message, unknown symbol). Any other failure (for example the database is down) is
 * rethrown so the container's error handler retries it, see {@link KafkaConsumerConfig}.
 */
@Component
class SignalListener {

    static final String TOPIC = "signal.generated";

    private static final Logger log = LoggerFactory.getLogger(SignalListener.class);

    private final SignalMapper mapper;
    private final OrderService orders;
    private final SignalProperties properties;
    private final Clock clock;

    SignalListener(SignalMapper mapper, OrderService orders, SignalProperties properties, Clock clock) {
        this.mapper = mapper;
        this.orders = orders;
        this.properties = properties;
        this.clock = clock;
    }

    @KafkaListener(topics = TOPIC, autoStartup = "${vajra.signals.enabled:true}")
    void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            SignalMapper.MappedSignal signal = mapper.toSignal(record.value());
            Duration age = Duration.between(signal.generatedAt(), clock.instant());
            if (age.compareTo(properties.maxAge()) > 0) {
                // Recorded for audit but never traded: e.g. it sat in Kafka while this service was down.
                PlaceOrderResult result = orders.rejectWithoutRisk(signal.intent(), OrderService.STALE_SIGNAL);
                if (result.created()) {
                    log.warn("Stale signal {} rejected: age {} exceeds max {} (offset {})", signal.intent().signalId(),
                            age, properties.maxAge(), record.offset());
                } else {
                    logPlaced(record, result);
                }
            } else {
                logPlaced(record, orders.place(signal.intent()));
            }
        } catch (InvalidSignalException e) {
            skip(record, e.getMessage());
        } catch (ApiException e) {
            if (!isPermanent(e)) {
                throw e;
            }
            skip(record, e.getMessage());
        }
        ack.acknowledge();
    }

    private static void logPlaced(ConsumerRecord<String, byte[]> record, PlaceOrderResult result) {
        String signalId = SignalMapper.signalIdOf(record.value());
        Order order = result.order();
        if (!result.created() && !order.state().isTerminal()) {
            // A replay never re-runs the order: an earlier attempt died after the NEW row was committed.
            log.error("signal {}: order {} stuck in {}, not re-processed", signalId, order.id(), order.state());
            return;
        }
        log.info("Signal {} -> order {} {} (offset {})", signalId, order.id(),
                result.created() ? "placed" : "already placed", record.offset());
    }

    private static boolean isPermanent(ApiException e) {
        return !e.retryable() && (e.status() == HttpStatus.BAD_REQUEST || e.status() == HttpStatus.NOT_FOUND
                || e.status() == HttpStatus.CONFLICT);
    }

    private static void skip(ConsumerRecord<String, byte[]> record, String reason) {
        log.error("Skipping signal {} at offset {}: {}", SignalMapper.signalIdOf(record.value()), record.offset(), reason);
    }
}
