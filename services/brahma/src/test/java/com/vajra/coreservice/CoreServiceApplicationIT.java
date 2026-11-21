package com.vajra.coreservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vajra.coreservice.testsupport.HttpTestClient;
import com.vajra.coreservice.testsupport.TestDatabase;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "vajra.scheduling.enabled=false")
class CoreServiceApplicationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
        registry.add("RISK_API_TOKEN", () -> "test-risk-token");
        registry.add("CORE_INTERNAL_TOKEN", () -> "test-internal-token");
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
        SpringApplicationBuilder app = new SpringApplicationBuilder(CoreServiceApplication.class)
                .properties("DB_URL=jdbc:postgresql://localhost:1/vajra", "DB_USER=core_svc", "server.port=0");

        assertThatThrownBy(app::run).hasStackTraceContaining("DB_PASSWORD");
    }

    @Test
    void contextFailsWithBlankRiskApiToken() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(CoreServiceApplication.class)
                .properties("DB_URL=jdbc:postgresql://localhost:1/vajra", "DB_USER=core_svc", "DB_PASSWORD=x",
                        "spring.flyway.enabled=false", "vajra.scheduling.enabled=false", "RISK_API_TOKEN= ",
                        "CORE_INTERNAL_TOKEN=x", "server.port=0");

        assertThatThrownBy(app::run).hasStackTraceContaining("vajra.risk").hasStackTraceContaining("apiToken");
    }
}
