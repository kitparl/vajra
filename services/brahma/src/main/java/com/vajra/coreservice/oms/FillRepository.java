package com.vajra.coreservice.oms;

import com.vajra.coreservice.connector.Venue;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Table {@code core.fill}. */
@Repository
public class FillRepository {

    private final JdbcClient jdbc;

    public FillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false when a fill with the same {@code (venue, venueFillId)} already exists (replay). */
    public boolean insertIfNew(Fill f) {
        int rows = jdbc.sql("insert into fill (id, order_id, venue, venue_fill_id, price, size, fee, liquidity, at) "
                        + "values (:id, :orderId, :venue, :venueFillId, :price, :size, :fee, :liquidity, :at) "
                        + "on conflict (venue, venue_fill_id) do nothing")
                .param("id", f.id())
                .param("orderId", f.orderId())
                .param("venue", f.venue().name())
                .param("venueFillId", f.venueFillId())
                .param("price", f.price())
                .param("size", f.size())
                .param("fee", f.fee())
                .param("liquidity", f.liquidity())
                .param("at", OffsetDateTime.ofInstant(f.at(), ZoneOffset.UTC))
                .update();
        return rows == 1;
    }

    public List<Fill> findByOrder(UUID orderId) {
        return jdbc.sql("select id, order_id, venue, venue_fill_id, price, size, fee, liquidity, at "
                        + "from fill where order_id = :orderId order by at, id")
                .param("orderId", orderId)
                .query((rs, row) -> new Fill(
                        rs.getObject("id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        Venue.valueOf(rs.getString("venue")),
                        rs.getString("venue_fill_id"),
                        rs.getBigDecimal("price"),
                        rs.getLong("size"),
                        rs.getBigDecimal("fee"),
                        rs.getString("liquidity"),
                        rs.getObject("at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
