package com.vajra.coreservice.audit;

import com.vajra.coreservice.audit.AuditEventRepository.OutboxRow;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Transactional outbox relay: sends unpublished {@code audit_event} rows to Kafka (topic = event type, key = symbol)
 * and marks a row published only after the broker acknowledges it. A failure stops the batch so order is kept; the
 * rows stay and are retried on the next run. Kafka being down never affects order handling.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final AuditEventRepository events;
    private final KafkaTemplate<String, byte[]> kafka;
    private final Clock clock;
    private final int batchSize;

    public OutboxPublisher(AuditEventRepository events, KafkaTemplate<String, byte[]> kafka, Clock clock,
            @Value("${vajra.outbox.batch-size}") int batchSize) {
        this.events = events;
        this.kafka = kafka;
        // Failures are logged once per run below (WARN, no stack trace); the default listener would add ERROR + trace.
        kafka.setProducerListener(new ProducerListener<>() {});
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${vajra.outbox.interval}")
    public void run() {
        publishBatch();
    }

    /** Returns the number of rows sent and marked published. */
    public int publishBatch() {
        List<OutboxRow> rows = events.unpublished(batchSize);
        int sent = 0;
        for (OutboxRow row : rows) {
            try {
                kafka.send(row.eventType(), row.key(), row.payload()).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Outbox publish interrupted after {} of {} rows", sent, rows.size());
                return sent;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.warn("Outbox publish to {} failed after {} of {} rows (will retry): {}", row.eventType(), sent,
                        rows.size(), e.toString());
                return sent;
            }
            events.markPublished(row.id(), clock.instant());
            sent++;
        }
        return sent;
    }
}
