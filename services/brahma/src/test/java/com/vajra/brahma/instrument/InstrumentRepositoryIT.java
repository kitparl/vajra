package com.vajra.brahma.instrument;

import static org.assertj.core.api.Assertions.assertThat;

import com.vajra.brahma.connector.Venue;
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
class InstrumentRepositoryIT {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    static InstrumentRepository repository;

    @BeforeAll
    static void migrate() {
        repository = new InstrumentRepository(JdbcClient.create(TestDatabase.migratedDataSource(POSTGRES)));
    }

    @Test
    void upsertUpdatesExistingRow() {
        repository.upsertAll(List.of(instrument("BTCUSD", "0.5", true), instrument("SOLUSD", "0.01", false)));
        repository.upsertAll(List.of(instrument("BTCUSD", "1.0", true)));

        List<Instrument> btc = repository.findAll().stream().filter(i -> i.symbol().equals("BTCUSD")).toList();
        assertThat(btc).hasSize(1);
        assertThat(btc.getFirst().tickSize()).isEqualByComparingTo("1.0");
        assertThat(btc.getFirst().contractValue()).isEqualByComparingTo("0.001");
        assertThat(repository.findTradingEnabled()).extracting(Instrument::symbol).containsExactly("BTCUSD");
        assertThat(repository.find(Venue.DELTA_TESTNET, "SOLUSD")).isPresent();
        assertThat(repository.find(Venue.DELTA_TESTNET, "NOPE")).isEmpty();
    }

    private static Instrument instrument(String symbol, String tick, boolean enabled) {
        return new Instrument(Venue.DELTA_TESTNET, symbol, symbol.hashCode(), "perpetual_futures",
                new BigDecimal("0.001"), new BigDecimal(tick), BigDecimal.ONE, new BigDecimal("0.0002"),
                new BigDecimal("0.0005"), enabled, Instant.parse("2026-11-21T10:00:00Z"));
    }
}
