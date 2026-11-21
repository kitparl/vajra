package com.vajra.brahma.instrument;

import com.vajra.brahma.connector.Venue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Table {@code core.instrument}. */
@Repository
public class InstrumentRepository {

    private static final String COLUMNS = "venue, symbol, venue_product_id, contract_type, contract_value, tick_size, "
            + "min_size, maker_fee, taker_fee, trading_enabled, updated_at";

    private final JdbcClient jdbc;

    public InstrumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertAll(List<Instrument> instruments) {
        for (Instrument i : instruments) {
            jdbc.sql("insert into instrument (" + COLUMNS + ") values "
                            + "(:venue, :symbol, :productId, :contractType, :contractValue, :tickSize, :minSize, :makerFee, "
                            + ":takerFee, :enabled, :updatedAt) "
                            + "on conflict (venue, symbol) do update set venue_product_id = excluded.venue_product_id, "
                            + "contract_type = excluded.contract_type, contract_value = excluded.contract_value, "
                            + "tick_size = excluded.tick_size, min_size = excluded.min_size, maker_fee = excluded.maker_fee, "
                            + "taker_fee = excluded.taker_fee, trading_enabled = excluded.trading_enabled, "
                            + "updated_at = excluded.updated_at")
                    .param("venue", i.venue().name())
                    .param("symbol", i.symbol())
                    .param("productId", i.venueProductId())
                    .param("contractType", i.contractType())
                    .param("contractValue", i.contractValue())
                    .param("tickSize", i.tickSize())
                    .param("minSize", i.minSize())
                    .param("makerFee", i.makerFee())
                    .param("takerFee", i.takerFee())
                    .param("enabled", i.tradingEnabled())
                    .param("updatedAt", OffsetDateTime.ofInstant(i.updatedAt(), ZoneOffset.UTC))
                    .update();
        }
    }

    public List<Instrument> findAll() {
        return jdbc.sql("select " + COLUMNS + " from instrument order by venue, symbol").query(InstrumentRepository::map).list();
    }

    public List<Instrument> findTradingEnabled() {
        return jdbc.sql("select " + COLUMNS + " from instrument where trading_enabled order by venue, symbol")
                .query(InstrumentRepository::map)
                .list();
    }

    public Optional<Instrument> find(Venue venue, String symbol) {
        return jdbc.sql("select " + COLUMNS + " from instrument where venue = :venue and symbol = :symbol")
                .param("venue", venue.name())
                .param("symbol", symbol)
                .query(InstrumentRepository::map)
                .optional();
    }

    public Optional<Long> idOf(Venue venue, String symbol) {
        return jdbc.sql("select id from instrument where venue = :venue and symbol = :symbol")
                .param("venue", venue.name())
                .param("symbol", symbol)
                .query(Long.class)
                .optional();
    }

    private static Instrument map(ResultSet rs, int row) throws SQLException {
        return new Instrument(
                Venue.valueOf(rs.getString("venue")),
                rs.getString("symbol"),
                rs.getLong("venue_product_id"),
                rs.getString("contract_type"),
                rs.getBigDecimal("contract_value"),
                rs.getBigDecimal("tick_size"),
                rs.getBigDecimal("min_size"),
                rs.getBigDecimal("maker_fee"),
                rs.getBigDecimal("taker_fee"),
                rs.getBoolean("trading_enabled"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
