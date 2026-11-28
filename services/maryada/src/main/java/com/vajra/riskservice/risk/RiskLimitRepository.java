package com.vajra.riskservice.risk;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Table {@code risk.risk_limit}. */
@Repository
public class RiskLimitRepository {

    private final JdbcClient jdbc;

    public RiskLimitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public RiskLimits load() {
        Map<String, Long> values = jdbc.sql("select name, value from risk_limit")
                .query((rs, n) -> Map.entry(rs.getString("name"), rs.getLong("value")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new RiskLimits(
                require(values, "max_order_size"),
                require(values, "max_abs_position"),
                require(values, "max_orders_per_minute"));
    }

    public List<RiskLimitRow> listAll() {
        return jdbc.sql("select name, value, updated_at from risk_limit order by name")
                .query((rs, n) -> new RiskLimitRow(
                        rs.getString("name"), rs.getLong("value"), rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    public record RiskLimitRow(String name, long value, Instant updatedAt) {}

    private static long require(Map<String, Long> values, String name) {
        Long value = values.get(name);
        if (value == null) {
            throw new IllegalStateException("risk_limit row missing: " + name);
        }
        return value;
    }
}
