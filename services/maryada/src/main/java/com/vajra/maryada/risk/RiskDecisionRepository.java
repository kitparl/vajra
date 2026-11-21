package com.vajra.maryada.risk;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Table {@code risk.risk_decision}: audit of every approval request and answer. */
@Repository
public class RiskDecisionRepository {

    private final JdbcClient jdbc;

    public RiskDecisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, ApprovalRequest r, Verdict verdict, Instant at) {
        jdbc.sql("""
                        insert into risk_decision (id, client_order_id, venue, symbol, side, size, order_type,
                            limit_price, reduce_only, source, current_position, approved, reason, decided_at)
                        values (:id, :clientOrderId, :venue, :symbol, :side, :size, :orderType,
                            :limitPrice, :reduceOnly, :source, :currentPosition, :approved, :reason, :at)""")
                .param("id", id)
                .param("clientOrderId", r.clientOrderId())
                .param("venue", r.venue())
                .param("symbol", r.symbol())
                .param("side", r.side())
                .param("size", r.size())
                .param("orderType", r.orderType())
                .param("limitPrice", r.limitPrice())
                .param("reduceOnly", r.reduceOnly())
                .param("source", r.source())
                .param("currentPosition", r.currentPosition())
                .param("approved", verdict.approved())
                .param("reason", verdict.reason())
                .param("at", Timestamp.from(at))
                .update();
    }

    /** Approved orders since {@code cutoff}; kill-switch flatten orders are exempt from the rate limit. */
    public long countApprovedSince(Instant cutoff) {
        return jdbc.sql("""
                        select count(*) from risk_decision
                        where approved and source is distinct from 'KILL_SWITCH' and decided_at > :cutoff""")
                .param("cutoff", Timestamp.from(cutoff))
                .query(Long.class)
                .single();
    }
}
