package com.vajra.brahma.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.sun.net.httpserver.HttpServer;
import com.vajra.brahma.connector.Venue;
import com.vajra.brahma.oms.Order;
import com.vajra.brahma.oms.OrderSource;
import com.vajra.brahma.oms.OrderState;
import com.vajra.brahma.oms.OrderType;
import com.vajra.brahma.oms.Side;
import com.vajra.brahma.oms.TimeInForce;
import com.vajra.brahma.position.Position;
import com.vajra.brahma.position.PositionRepository;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MaryadaRiskGateTest {

    private static final String BASE_URL = "http://maryada:8082";
    private static final String APPROVALS = BASE_URL + "/api/v1/approvals";
    private static final String TOKEN = "risk-token";
    private static final long INSTRUMENT_ID = 7L;
    private static final UUID DECISION_ID = UUID.fromString("3f1c0a52-8e0b-4d55-9a57-6b0f2d1c9e11");

    private final PositionRepository positions = mock(PositionRepository.class);
    private MockRestServiceServer server;
    private MaryadaRiskGate gate;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gate = new MaryadaRiskGate(builder, BASE_URL, TOKEN, positions);
        when(positions.find(Venue.PAPER, INSTRUMENT_ID)).thenReturn(Optional.empty());
    }

    @Test
    void approvedAnswerApproves() {
        when(positions.find(Venue.PAPER, INSTRUMENT_ID)).thenReturn(Optional.of(position(-3)));
        server.expect(requestTo(APPROVALS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(content().json("""
                        {"clientOrderId":"cid-1","venue":"PAPER","symbol":"BTCUSD","side":"BUY","size":1,
                         "orderType":"LIMIT","limitPrice":50000.5,"reduceOnly":false,"source":"MANUAL",
                         "currentPosition":-3}""", JsonCompareMode.STRICT))
                .andRespond(withSuccess(answer("APPROVED", null), MediaType.APPLICATION_JSON));

        RiskDecision decision = gate.approve(order());

        server.verify();
        assertThat(decision.approved()).isTrue();
        assertThat(decision.reason()).isNull();
        assertThat(decision.decisionId()).isEqualTo(DECISION_ID);
    }

    @Test
    void rejectedAnswerRejectsWithReason() {
        server.expect(requestTo(APPROVALS))
                .andExpect(content().json("{\"currentPosition\":0}"))
                .andRespond(withSuccess(answer("REJECTED", "\"POSITION_LIMIT\""), MediaType.APPLICATION_JSON));

        RiskDecision decision = gate.approve(order());

        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason()).isEqualTo("POSITION_LIMIT");
        assertThat(decision.decisionId()).isEqualTo(DECISION_ID);
    }

    @Test
    void serverErrorRejects() {
        server.expect(requestTo(APPROVALS)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertRiskCheckFailed(gate.approve(order()));
    }

    @Test
    void malformedAnswerRejects() {
        server.expect(requestTo(APPROVALS)).andRespond(withSuccess("{\"foo\":1}", MediaType.APPLICATION_JSON));

        assertRiskCheckFailed(gate.approve(order()));
    }

    @Test
    void unknownDecisionRejects() {
        server.expect(requestTo(APPROVALS))
                .andRespond(withSuccess(answer("MAYBE", null), MediaType.APPLICATION_JSON));

        assertRiskCheckFailed(gate.approve(order()));
    }

    @Test
    void unauthorizedRejects() {
        server.expect(requestTo(APPROVALS)).andRespond(withUnauthorizedRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"unauthorized\"}"));

        assertRiskCheckFailed(gate.approve(order()));
    }

    @Test
    void timeoutRejects() throws Exception {
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/api/v1/approvals", exchange -> {
            try {
                Thread.sleep(2000);
                byte[] body = answer("APPROVED", null).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        slow.start();
        try {
            MaryadaRiskGate timed = new MaryadaRiskGate(RiskClientConfig.timedClient(),
                    "http://127.0.0.1:" + slow.getAddress().getPort(), TOKEN, positions);

            long start = System.nanoTime();
            RiskDecision decision = timed.approve(order());
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertRiskCheckFailed(decision);
            assertThat(took).isLessThan(Duration.ofSeconds(1));
        } finally {
            slow.stop(0);
        }
    }

    private static void assertRiskCheckFailed(RiskDecision decision) {
        assertThat(decision.approved()).isFalse();
        assertThat(decision.reason()).isEqualTo("RISK_CHECK_FAILED");
        assertThat(decision.decisionId()).isNull();
    }

    private static String answer(String decision, String reasonJson) {
        return "{\"decisionId\":\"" + DECISION_ID + "\",\"decision\":\"" + decision + "\",\"reason\":" + reasonJson + "}";
    }

    private static Position position(long size) {
        return new Position(Venue.PAPER, INSTRUMENT_ID, "BTCUSD", size, new BigDecimal("50000"), BigDecimal.ZERO,
                BigDecimal.ZERO, Instant.parse("2026-11-21T10:00:00Z"), 1L);
    }

    private static Order order() {
        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        return new Order(UUID.randomUUID(), "cid-1", "key-1", Venue.PAPER, INSTRUMENT_ID, "BTCUSD", Side.BUY,
                OrderType.LIMIT, 1L, new BigDecimal("50000.5"), TimeInForce.GTC, false, OrderState.NEW, 0L, null,
                BigDecimal.ZERO, null, OrderSource.MANUAL, null, now, now, 0L);
    }
}
