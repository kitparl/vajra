package com.vajra.riskservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RiskServiceApplicationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
        registry.add("RISK_API_TOKEN", () -> "test-risk-token");
        registry.add("CORE_INTERNAL_TOKEN", () -> "core-token");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    DataSource dataSource;

    @Test
    void healthIsUp() throws Exception {
        HttpResponse<String> response = HttpTestClient.get(port, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void flywayRanInOwnSchema() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String sql = "select count(*) from information_schema.tables where table_schema = ? and table_name = 'flyway_schema_history'";

        assertThat(jdbc.queryForObject(sql, Integer.class, TestDatabase.SCHEMA)).isEqualTo(1);
        assertThat(jdbc.queryForObject(sql, Integer.class, "public")).isZero();
    }

    @Test
    void contextFailsWithoutDbPassword() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(RiskServiceApplication.class)
                .properties("DB_URL=jdbc:postgresql://localhost:1/vajra", "DB_USER=risk_svc", "RISK_API_TOKEN=t", "server.port=0");

        assertThatThrownBy(app::run).hasStackTraceContaining("DB_PASSWORD");
    }
}
