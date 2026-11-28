package com.vajra.coreservice.marketdata;

import com.vajra.coreservice.connector.Candle;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Table {@code core.candle_1m}. */
@Repository
public class CandleRepository {

    private final JdbcClient jdbc;

    public CandleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertAll(long instrumentId, List<Candle> candles) {
        for (Candle c : candles) {
            jdbc.sql("insert into candle_1m (instrument_id, open_time, open, high, low, close, volume) "
                            + "values (:id, :openTime, :open, :high, :low, :close, :volume) "
                            + "on conflict (instrument_id, open_time) do update set open = excluded.open, "
                            + "high = excluded.high, low = excluded.low, close = excluded.close, volume = excluded.volume")
                    .param("id", instrumentId)
                    .param("openTime", utc(c.openTime()))
                    .param("open", c.open())
                    .param("high", c.high())
                    .param("low", c.low())
                    .param("close", c.close())
                    .param("volume", c.volume())
                    .update();
        }
    }

    public Optional<Instant> lastOpenTime(long instrumentId) {
        return jdbc.sql("select max(open_time) from candle_1m where instrument_id = :id")
                .param("id", instrumentId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    public Optional<Instant> firstOpenTime(long instrumentId) {
        return jdbc.sql("select min(open_time) from candle_1m where instrument_id = :id")
                .param("id", instrumentId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /** Candles with {@code from <= open_time < to}, ascending. */
    public List<Candle> find(long instrumentId, String symbol, Instant from, Instant to) {
        return jdbc.sql("select open_time, open, high, low, close, volume from candle_1m "
                        + "where instrument_id = :id and open_time >= :from and open_time < :to order by open_time")
                .param("id", instrumentId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query((rs, row) -> new Candle(symbol, rs.getObject("open_time", OffsetDateTime.class).toInstant(),
                        rs.getBigDecimal("open"), rs.getBigDecimal("high"), rs.getBigDecimal("low"),
                        rs.getBigDecimal("close"), rs.getBigDecimal("volume")))
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
