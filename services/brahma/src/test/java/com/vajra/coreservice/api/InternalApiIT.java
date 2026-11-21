package com.vajra.coreservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vajra.coreservice.CoreServiceApplication;
import com.vajra.coreservice.risk.TradingStateRepository;
import com.vajra.coreservice.testsupport.ApprovingRiskGate;
import com.vajra.coreservice.testsupport.HttpTestClient;
import com.vajra.coreservice.testsupport.TestDatabase;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "vajra.scheduling.enabled=false")
class InternalApiIT {

    private static final String TOKEN = "test-internal-token";
    private static final String RISK_TOKEN = "test-risk-token";
    private static final String KILL = "/internal/v1/kill";
    private static final String KILL_BODY = "{\"reason\":\"test\"}";

    @TestConfiguration
    static class RiskOverride {
        @Bean
        @Primary
        ApprovingRiskGate approvingRiskGate() {
            return new ApprovingRiskGate();
        }
    }

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
        registry.add("RISK_API_TOKEN", () -> RISK_TOKEN);
        registry.add("CORE_INTERNAL_TOKEN", () -> TOKEN);
    }

    @Autowired
    TradingStateRepository tradingState;

    @Value("${local.server.port}")
    int port;

    @BeforeEach
    void notHalted() {
        tradingState.setHalted(false, null, Instant.now());
    }

    @Test
    void killNeedsInternalToken() throws Exception {
        HttpResponse<String> none = post(KILL, KILL_BODY, null);
        HttpResponse<String> riskToken = post(KILL, KILL_BODY, "Bearer " + RISK_TOKEN);

        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.body()).isEqualTo("{\"error\":\"unauthorized\"}");
        assertThat(riskToken.statusCode()).isEqualTo(401);
        assertThat(post("/internal/v1/resume", "", "Bearer " + RISK_TOKEN).statusCode()).isEqualTo(401);
        assertThat(tradingState.halted()).isFalse();
    }

    @Test
    void killReturnsResult() throws Exception {
        HttpResponse<String> response = post(KILL, KILL_BODY, "Bearer " + TOKEN);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"cancelledOrders\":0").contains("\"flattenOrders\":0")
                .contains("\"flattened\":true");
        assertThat(tradingState.halted()).isTrue();
    }

    @Test
    void lowercaseBearerAccepted() throws Exception {
        assertThat(post(KILL, KILL_BODY, "bearer " + TOKEN).statusCode()).isEqualTo(200);
    }

    @Test
    void killWithoutReasonIs400() throws Exception {
        assertThat(post(KILL, "{}", "Bearer " + TOKEN).statusCode()).isEqualTo(400);
        assertThat(tradingState.halted()).isFalse();
    }

    @Test
    void resumeReturns204() throws Exception {
        post(KILL, KILL_BODY, "Bearer " + TOKEN);

        HttpResponse<String> response = post("/internal/v1/resume", "", "Bearer " + TOKEN);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(tradingState.halted()).isFalse();
    }

    @Test
    void pathParameterBypassIsBlocked() throws Exception {
        HttpResponse<String> response = post("/internal;x=y/v1/kill", KILL_BODY, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(tradingState.halted()).isFalse();
    }

    @Test
    void encodedPathBypassIsBlocked() throws Exception {
        HttpResponse<String> response = post("/%69nternal/v1/kill", KILL_BODY, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(tradingState.halted()).isFalse();
    }

    @Test
    void doubleSlashBypassIsBlocked() throws Exception {
        HttpResponse<String> response = post("//internal/v1/kill", KILL_BODY, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(tradingState.halted()).isFalse();
    }

    @Test
    void startupFailsWithoutInternalToken() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(CoreServiceApplication.class)
                .properties("DB_URL=jdbc:postgresql://localhost:1/vajra", "DB_USER=core_svc", "DB_PASSWORD=x",
                        "spring.flyway.enabled=false", "vajra.scheduling.enabled=false", "RISK_API_TOKEN=x",
                        "CORE_INTERNAL_TOKEN= ", "server.port=0");

        assertThatThrownBy(app::run).hasStackTraceContaining("vajra.internal").hasStackTraceContaining("token");
    }

    private HttpResponse<String> post(String path, String body, String authorization) throws Exception {
        Map<String, String> headers = authorization == null
                ? Map.of("Content-Type", "application/json")
                : Map.of("Content-Type", "application/json", "Authorization", authorization);
        return HttpTestClient.post(port, path, headers, body);
    }
}
