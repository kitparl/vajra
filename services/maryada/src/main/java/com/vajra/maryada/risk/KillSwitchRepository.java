package com.vajra.maryada.risk;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tables {@code risk.kill_switch} (single row) and {@code risk.kill_switch_event}. */
@Repository
public class KillSwitchRepository {

    private final JdbcClient jdbc;

    public KillSwitchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean halted() {
        return jdbc.sql("select halted from kill_switch where id = 1").query(Boolean.class).single();
    }

    public void setHalted(boolean halted, Instant at) {
        jdbc.sql("update kill_switch set halted = :halted, updated_at = :at where id = 1")
                .param("halted", halted)
                .param("at", Timestamp.from(at))
                .update();
    }

    /** The epoch of the last halt/resume change; a kill press moves it. */
    public Instant updatedAt() {
        return jdbc.sql("select updated_at from kill_switch where id = 1").query(Timestamp.class).single().toInstant();
    }

    /** Clears halted only if nothing changed the switch since {@code epoch}. Returns false if it did. */
    public boolean clearHaltedIf(Instant epoch, Instant now) {
        return jdbc.sql("update kill_switch set halted = false, updated_at = :now where id = 1 and updated_at = :epoch")
                .param("now", Timestamp.from(now))
                .param("epoch", Timestamp.from(epoch))
                .update() == 1;
    }

    public void addEvent(String action, String reason, Boolean flattened, Instant at) {
        jdbc.sql("insert into kill_switch_event (action, reason, flattened, at) values (:action, :reason, :flattened, :at)")
                .param("action", action)
                .param("reason", reason)
                .param("flattened", flattened)
                .param("at", Timestamp.from(at))
                .update();
    }

    public List<KillSwitchEvent> events(int limit) {
        return jdbc.sql("select id, action, reason, flattened, at from kill_switch_event order by id desc limit :limit")
                .param("limit", limit)
                .query((rs, n) -> new KillSwitchEvent(
                        rs.getLong("id"),
                        rs.getString("action"),
                        rs.getString("reason"),
                        (Boolean) rs.getObject("flattened"),
                        rs.getTimestamp("at").toInstant()))
                .list();
    }
}
