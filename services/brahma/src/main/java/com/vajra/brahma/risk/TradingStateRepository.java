package com.vajra.brahma.risk;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Table {@code core.trading_state}: the single-row local halt flag. */
@Repository
public class TradingStateRepository {

    private final JdbcClient jdbc;

    public TradingStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean halted() {
        return jdbc.sql("select halted from trading_state where id = 1").query(Boolean.class).single();
    }

    public void setHalted(boolean halted, String reason, Instant at) {
        jdbc.sql("update trading_state set halted = :halted, reason = :reason, updated_at = :at where id = 1")
                .param("halted", halted)
                .param("reason", reason)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }
}
