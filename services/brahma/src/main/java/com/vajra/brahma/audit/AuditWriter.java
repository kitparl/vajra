package com.vajra.brahma.audit;

import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.vajra.contracts.common.v1.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes the audit outbox ({@code core.audit_event}); always inside the caller's transaction. */
@Component
public class AuditWriter {

    private static final String PRODUCER = "core-service";

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditWriter(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String eventType, Message payload) {
        // Checked explicitly as well: the annotation has no effect when no Spring proxy is involved.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("audit event " + eventType + " must be written inside a transaction");
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        EventEnvelope envelope = EventEnvelope.newBuilder()
                .setEventId(id.toString())
                .setEventType(eventType)
                .setOccurredAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
                .setProducer(PRODUCER)
                .setPayload(Any.pack(payload))
                .build();
        jdbc.sql("insert into audit_event (id, event_type, payload, occurred_at) "
                        + "values (:id, :eventType, :payload, :occurredAt)")
                .param("id", id)
                .param("eventType", eventType)
                .param("payload", envelope.toByteArray())
                .param("occurredAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }
}
