package com.vajra.riskservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vajra.riskservice.HttpTestClient;
import com.vajra.riskservice.RiskServiceApplication;
import com.vajra.riskservice.TestDatabase;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApprovalApiIT {

    private static final String TOKEN = "test-risk-token";
    private static final String PATH = "/api/v1/approvals";

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

    private static String body(String clientOrderId, long size) {
        return """
                {"clientOrderId":"%s","venue":"PAPER","symbol":"BTCUSD","side":"BUY","size":%d,
                 "orderType":"MARKET","reduceOnly":false,"source":"MANUAL","currentPosition":0}"""
                .formatted(clientOrderId, size);
    }

    private HttpResponse<String> approve(String json) throws Exception {
        return HttpTestClient.postJson(port, PATH, json, TOKEN);
    }

    private long rows() {
        return jdbc.sql("select count(*) from risk_decision").query(Long.class).single();
    }

    @Test
    void approvesAndStoresDecision() throws Exception {
        HttpResponse<String> response = approve(body("o-1", 1));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"decision\":\"APPROVED\"").contains("\"decisionId\":\"");
        Map<String, Object> row = jdbc.sql("select id::text as id, approved from risk_decision where client_order_id = 'o-1'")
                .query().singleRow();
        assertThat(row.get("approved")).isEqualTo(true);
        assertThat(response.body()).contains((String) row.get("id"));
    }

    @Test
    void rejectsOverSizeAndStoresDecision() throws Exception {
        HttpResponse<String> response = approve(body("o-big", 2));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"decision\":\"REJECTED\"").contains("ORDER_SIZE_LIMIT");
        Map<String, Object> row = jdbc.sql("select approved, reason from risk_decision where client_order_id = 'o-big'")
                .query().singleRow();
        assertThat(row.get("approved")).isEqualTo(false);
        assertThat(row.get("reason")).isEqualTo("ORDER_SIZE_LIMIT");
    }

    @Test
    void missingTokenIs401() throws Exception {
        HttpResponse<String> response = HttpTestClient.postJson(port, PATH, body("o-2", 1), null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEqualTo("{\"error\":\"unauthorized\"}");
        assertThat(rows()).isZero();
    }

    @Test
    void wrongTokenIs401() throws Exception {
        HttpResponse<String> response = HttpTestClient.postJson(port, PATH, body("o-3", 1), "nope");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEqualTo("{\"error\":\"unauthorized\"}");
        assertThat(rows()).isZero();
    }

    @Test
    void invalidBodyIs400() throws Exception {
        assertThat(approve(body("o-4", 0)).statusCode()).isEqualTo(400);
        assertThat(approve(body("o-4", 1).replace("\"clientOrderId\":\"o-4\",", "")).statusCode()).isEqualTo(400);
        assertThat(approve(body("x".repeat(33), 1)).statusCode()).isEqualTo(400);
        assertThat(rows()).isZero();
    }

    @Test
    void eleventhOrderInAMinuteIsRejected() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(approve(body("r-" + i, 1)).body()).contains("APPROVED");
        }

        HttpResponse<String> eleventh = approve(body("r-10", 1));

        assertThat(eleventh.body()).contains("\"decision\":\"REJECTED\"").contains("ORDER_RATE_LIMIT");
    }

    @Test
    void concurrentApprovalsRespectOrderRateLimit() throws Exception {
        for (int i = 0; i < 9; i++) {
            assertThat(approve(body("c-" + i, 1)).body()).contains("APPROVED");
        }
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                String json = body("p-" + t, 1);
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return approve(json).body();
                }));
            }
            ready.await();
            go.countDown();
            long approved = 0;
            for (Future<String> f : results) {
                if (f.get().contains("\"decision\":\"APPROVED\"")) {
                    approved++;
                }
            }
            assertThat(approved).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("select count(*) from risk_decision where approved").query(Long.class).single())
                .isEqualTo(10);
    }

    @Test
    void pathParameterBypassIsBlocked() throws Exception {
        HttpResponse<String> response = HttpTestClient.postJson(port, "/api;x=y/v1/approvals", body("b-1", 1), null);

        assertThat(response.statusCode()).isNotEqualTo(200);
        assertThat(rows()).isZero();
    }

    @Test
    void encodedPathBypassIsBlocked() throws Exception {
        HttpResponse<String> response = HttpTestClient.postJson(port, "/%61pi/v1/approvals", body("b-2", 1), null);

        assertThat(response.statusCode()).isNotEqualTo(200);
        assertThat(rows()).isZero();
    }

    @Test
    void unknownPathNeedsToken() throws Exception {
        assertThat(HttpTestClient.get(port, "/api/v1/does-not-exist").statusCode()).isEqualTo(401);
    }

    @Test
    void lowercaseBearerAccepted() throws Exception {
        HttpResponse<String> response =
                HttpTestClient.postJsonWithAuthorization(port, PATH, body("b-3", 1), "bearer " + TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void healthNeedsNoToken() throws Exception {
        assertThat(HttpTestClient.get(port, "/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    void startupFailsWithoutRiskApiToken() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(RiskServiceApplication.class)
                .properties("DB_URL=jdbc:postgresql://localhost:1/vajra", "DB_USER=risk_svc", "DB_PASSWORD=x",
                        "RISK_API_TOKEN=", "CORE_INTERNAL_TOKEN=x", "server.port=0");

        assertThatThrownBy(app::run).hasStackTraceContaining("riskApiToken");
    }
}
