package com.vajra.gatewaybff.auth;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Writes gateway.audit_event. Detail must never contain secrets. */
@Repository
public class AuditEventRepository {

    static final String LOGIN_OK = "LOGIN_OK";
    static final String LOGIN_FAILED = "LOGIN_FAILED";
    static final String LOGIN_LOCKED = "LOGIN_LOCKED";
    static final String LOGOUT = "LOGOUT";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    AuditEventRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void record(String kind, Map<String, ?> detail) {
        jdbc.sql("insert into audit_event (occurred_at, kind, detail) values (?, ?, ?::jsonb)")
                .param(OffsetDateTime.ofInstant(clock.instant(), clock.getZone()))
                .param(kind)
                .param(mapper.writeValueAsString(detail))
                .update();
    }
}
