package com.vajra.brahma.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.vajra.brahma.connector.Candle;
import com.vajra.brahma.connector.Venue;
import com.vajra.brahma.instrument.Instrument;
import com.vajra.brahma.instrument.InstrumentRepository;
import com.vajra.brahma.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class CandleRepositoryIT {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    static CandleRepository candles;
    static long btcId;

    @BeforeAll
    static void setUp() {
        JdbcClient jdbc = JdbcClient.create(TestDatabase.migratedDataSource(POSTGRES));
        InstrumentRepository instruments = new InstrumentRepository(jdbc);
        instruments.upsertAll(List.of(new Instrument(Venue.DELTA_TESTNET, "BTCUSD", 84, "perpetual_futures",
                new BigDecimal("0.001"), new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"),
                new BigDecimal("0.0005"), true, Instant.parse("2026-11-21T10:00:00Z"))));
        btcId = instruments.idOf(Venue.DELTA_TESTNET, "BTCUSD").orElseThrow();
        candles = new CandleRepository(jdbc);
    }

    @Test
    void upsertIsIdempotentAndLatestWins() {
        candles.upsertAll(btcId, List.of(candle("10:00", "100"), candle("10:01", "200")));
        candles.upsertAll(btcId, List.of(candle("10:00", "101")));

        List<Candle> stored = candles.find(btcId, "BTCUSD", at("10:00"), at("10:02"));

        assertThat(stored).extracting(Candle::openTime).containsExactly(at("10:00"), at("10:01"));
        assertThat(stored.getFirst().close()).isEqualByComparingTo("101");
        assertThat(stored.getFirst().symbol()).isEqualTo("BTCUSD");
        assertThat(candles.find(btcId, "BTCUSD", at("10:00"), at("10:01"))).hasSize(1);
        assertThat(candles.lastOpenTime(btcId)).contains(at("10:01"));
    }

    private static Candle candle(String hhmm, String close) {
        BigDecimal c = new BigDecimal(close);
        return new Candle("BTCUSD", at(hhmm), c, c, c, c, BigDecimal.TEN);
    }

    private static Instant at(String hhmm) {
        return Instant.parse("2026-11-21T" + hhmm + ":00Z");
    }
}
