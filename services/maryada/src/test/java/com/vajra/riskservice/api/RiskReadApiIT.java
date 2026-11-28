package com.vajra.riskservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.vajra.riskservice.HttpTestClient;
import com.vajra.riskservice.TestDatabase;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RiskReadApiIT {

    private static final String TOKEN = "test-risk-token";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
        registry.add("RISK_API_TOKEN", () -> TOKEN);
        registry.add("CORE_INTERNAL_TOKEN", () -> "core-token");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from risk_decision").update();
    }

    private void decision(String clientOrderId, boolean approved, String reason, Instant at) {
        jdbc.sql("""
                        insert into risk_decision (id, client_order_id, venue, symbol, side, size, order_type,
                            approved, reason, decided_at)
                        values (:id, :c, 'PAPER', 'BTCUSD', 'BUY', 3, 'MARKET', :a, :r, :at)""")
                .param("id", UUID.randomUUID())
                .param("c", clientOrderId)
                .param("a", approved)
                .param("r", reason)
                .param("at", Timestamp.from(at))
                .update();
    }

    private JsonNode get(String path, int expectedStatus) throws Exception {
        HttpResponse<String> response = HttpTestClient.getWithToken(port, path, TOKEN);
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        return JSON.readTree(response.body());
    }

    @Test
    void limitsListed() throws Exception {
        JsonNode limits = get("/api/v1/limits", 200);

        assertThat(limits).hasSize(3);
        assertThat(limits.get(0).get("name").asText()).isEqualTo("max_abs_position");
        assertThat(limits.get(0).get("value").asLong()).isEqualTo(5);
        assertThat(limits.get(1).get("name").asText()).isEqualTo("max_order_size");
        assertThat(limits.get(2).get("name").asText()).isEqualTo("max_orders_per_minute");
        assertThat(limits.get(0).get("updatedAt").asText()).isNotBlank();
    }

    @Test
    void rejectionsNewestFirstAndCapped() throws Exception {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            decision("rej-" + i, false, "MAX_ORDER_SIZE", base.plusSeconds(i));
        }
        decision("ok-1", true, null, base.plusSeconds(100));

        JsonNode all = get("/api/v1/rejections", 200);
        assertThat(all).hasSize(5);
        assertThat(all.get(0).get("clientOrderId").asText()).isEqualTo("rej-4");
        assertThat(all.get(4).get("clientOrderId").asText()).isEqualTo("rej-0");
        JsonNode first = all.get(0);
        assertThat(first.get("decisionId").asText()).isNotBlank();
        assertThat(first.get("venue").asText()).isEqualTo("PAPER");
        assertThat(first.get("symbol").asText()).isEqualTo("BTCUSD");
        assertThat(first.get("side").asText()).isEqualTo("BUY");
        assertThat(first.get("size").asLong()).isEqualTo(3);
        assertThat(first.get("orderType").asText()).isEqualTo("MARKET");
        assertThat(first.get("reason").asText()).isEqualTo("MAX_ORDER_SIZE");
        assertThat(first.get("decidedAt").asText()).isNotBlank();

        JsonNode capped = get("/api/v1/rejections?limit=2", 200);
        assertThat(capped).hasSize(2);
        assertThat(capped.get(0).get("clientOrderId").asText()).isEqualTo("rej-4");
        assertThat(capped.get(1).get("clientOrderId").asText()).isEqualTo("rej-3");
    }

    @Test
    void rejectionsLimitOutOfRangeIs400() throws Exception {
        for (String limit : new String[] {"0", "-1", "201", "abc"}) {
            JsonNode body = get("/api/v1/rejections?limit=" + limit, 400);
            assertThat(body.get("error").asText()).isNotBlank();
        }
        assertThat(get("/api/v1/rejections?limit=200", 200)).isEmpty();
        assertThat(get("/api/v1/rejections?limit=1", 200)).isEmpty();
    }

    @Test
    void readEndpointsNeedToken() throws Exception {
        assertThat(HttpTestClient.get(port, "/api/v1/limits").statusCode()).isEqualTo(401);
        assertThat(HttpTestClient.get(port, "/api/v1/rejections").statusCode()).isEqualTo(401);
    }
}
