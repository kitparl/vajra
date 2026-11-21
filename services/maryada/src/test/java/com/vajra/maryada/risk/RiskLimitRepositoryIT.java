package com.vajra.maryada.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vajra.maryada.TestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class RiskLimitRepositoryIT {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    static JdbcClient jdbc;
    static RiskLimitRepository repository;

    @BeforeAll
    static void migrate() {
        jdbc = JdbcClient.create(TestDatabase.migratedDataSource(POSTGRES));
        repository = new RiskLimitRepository(jdbc);
    }

    @Test
    void seededLimitsLoad() {
        assertThat(repository.load()).isEqualTo(new RiskLimits(1, 5, 10));
    }

    @Test
    void killSwitchSeededNotHalted() {
        assertThat(jdbc.sql("select halted from kill_switch where id = 1").query(Boolean.class).single())
                .isFalse();
    }

    @Test
    void missingLimitRowNamedInError() {
        jdbc.sql("update risk_limit set name = 'tmp' where name = 'max_abs_position'").update();
        try {
            assertThatThrownBy(repository::load).isInstanceOf(IllegalStateException.class).hasMessageContaining("max_abs_position");
        } finally {
            jdbc.sql("update risk_limit set name = 'max_abs_position' where name = 'tmp'").update();
        }
    }
}
