package com.vajra.coreservice.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the audit outbox ({@code core.audit_event}) for the publisher. Rows are written by {@link AuditWriter}. */
@Repository
public class AuditEventRepository {

    /** A row waiting for Kafka: {@code eventType} is the topic, {@code payload} the serialized envelope. */
    public record OutboxRow(UUID id, String eventType, String key, byte[] payload) {}

    private final JdbcClient jdbc;

    public AuditEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Oldest first, so per-key order is kept on the topic. */
    public List<OutboxRow> unpublished(int limit) {
        return jdbc.sql("select id, event_type, event_key, payload from audit_event "
                        + "where published_at is null order by occurred_at, id limit :limit")
                .param("limit", limit)
                .query((rs, n) -> new OutboxRow(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("event_key"), rs.getBytes("payload")))
                .list();
    }

    public void markPublished(UUID id, Instant at) {
        jdbc.sql("update audit_event set published_at = :at where id = :id")
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("id", id)
                .update();
    }
}
