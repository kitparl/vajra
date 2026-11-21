package com.vajra.brahma.position;

import com.vajra.brahma.connector.Venue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Table {@code core.position}. The symbol comes from a join on {@code instrument}. */
@Repository
public class PositionRepository {

    private static final String SELECT = "select p.venue, p.instrument_id, i.symbol, p.size, p.avg_entry_price, "
            + "p.realized_pnl, p.fees_paid, p.updated_at, p.version from position p "
            + "join instrument i on i.id = p.instrument_id ";

    private final JdbcClient jdbc;

    public PositionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Position> find(Venue venue, long instrumentId) {
        return jdbc.sql(SELECT + "where p.venue = :venue and p.instrument_id = :instrumentId")
                .param("venue", venue.name())
                .param("instrumentId", instrumentId)
                .query(PositionRepository::map)
                .optional();
    }

    public List<Position> findAll() {
        return jdbc.sql(SELECT + "order by p.venue, i.symbol").query(PositionRepository::map).list();
    }

    /**
     * Inserts when {@code p.version() == 0}; otherwise updates with a version check. A stale copy throws
     * {@link OptimisticLockingFailureException}, including a version-0 copy of a row that now exists.
     * Returns the saved position with its new version.
     */
    public Position save(Position p) {
        if (p.version() == 0) {
            insert(p);
        } else {
            update(p);
        }
        return withVersion(p, p.version() + 1);
    }

    private void insert(Position p) {
        try {
            doInsert(p);
        } catch (DuplicateKeyException e) {
            throw new OptimisticLockingFailureException(
                    "position " + p.venue() + "/" + p.instrumentId() + " already exists (stale version-0 copy)", e);
        }
    }

    private void doInsert(Position p) {
        jdbc.sql("insert into position (venue, instrument_id, size, avg_entry_price, realized_pnl, fees_paid, "
                        + "updated_at, version) values (:venue, :instrumentId, :size, :avg, :pnl, :fees, :updatedAt, 1)")
                .param("venue", p.venue().name())
                .param("instrumentId", p.instrumentId())
                .param("size", p.size())
                .param("avg", p.avgEntryPrice())
                .param("pnl", p.realizedPnl())
                .param("fees", p.feesPaid())
                .param("updatedAt", utc(p))
                .update();
    }

    private void update(Position p) {
        int rows = jdbc.sql("update position set size = :size, avg_entry_price = :avg, realized_pnl = :pnl, "
                        + "fees_paid = :fees, updated_at = :updatedAt, version = version + 1 "
                        + "where venue = :venue and instrument_id = :instrumentId and version = :version")
                .param("size", p.size())
                .param("avg", p.avgEntryPrice())
                .param("pnl", p.realizedPnl())
                .param("fees", p.feesPaid())
                .param("updatedAt", utc(p))
                .param("venue", p.venue().name())
                .param("instrumentId", p.instrumentId())
                .param("version", p.version())
                .update();
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                    "position " + p.venue() + "/" + p.instrumentId() + " changed concurrently (version " + p.version() + ")");
        }
    }

    private static Position withVersion(Position p, long version) {
        return new Position(p.venue(), p.instrumentId(), p.symbol(), p.size(), p.avgEntryPrice(), p.realizedPnl(),
                p.feesPaid(), p.updatedAt(), version);
    }

    private static Position map(ResultSet rs, int row) throws SQLException {
        return new Position(
                Venue.valueOf(rs.getString("venue")),
                rs.getLong("instrument_id"),
                rs.getString("symbol"),
                rs.getLong("size"),
                rs.getBigDecimal("avg_entry_price"),
                rs.getBigDecimal("realized_pnl"),
                rs.getBigDecimal("fees_paid"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getLong("version"));
    }

    private static OffsetDateTime utc(Position p) {
        return OffsetDateTime.ofInstant(p.updatedAt(), ZoneOffset.UTC);
    }
}
