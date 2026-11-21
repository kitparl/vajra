package com.vajra.brahma.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.vajra.brahma.connector.Quote;
import com.vajra.brahma.connector.Venue;
import com.vajra.brahma.instrument.Instrument;
import com.vajra.brahma.instrument.InstrumentRepository;
import com.vajra.brahma.marketdata.QuoteCache;
import com.vajra.brahma.testsupport.HttpTestClient;
import com.vajra.brahma.testsupport.MutableClock;
import com.vajra.brahma.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "vajra.scheduling.enabled=false")
class OrderApiIT {

    private static final Instant START = Instant.parse("2026-11-21T10:00:00Z");

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
    }

    @Autowired
    InstrumentRepository instruments;

    @Autowired
    QuoteCache quotes;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Value("${local.server.port}")
    int port;

    @BeforeEach
    void resetData() {
        for (String table : List.of("audit_event", "fill", "order_transition", "orders", "position")) {
            jdbc.sql("delete from " + table).update();
        }
        instruments.upsertAll(List.of(new Instrument(Venue.DELTA_TESTNET, "BTCUSD", 84, "perpetual_futures",
                new BigDecimal("0.001"), new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"),
                new BigDecimal("0.0005"), true, START)));
        quote("50000", "50010", "50005");
    }

    @Test
    void placeMarketOrderReturns201ThenSameKeyReturns200() throws Exception {
        HttpResponse<String> first = place("api-1", market("BUY", 1));
        HttpResponse<String> second = place("api-1", market("BUY", 1));

        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"state\":\"FILLED\"").contains("\"avgFillPrice\":\"50010\"");
        assertThat(idOf(second.body())).isEqualTo(idOf(first.body()));
        assertThat(second.body()).contains("\"state\":\"FILLED\"").contains("\"avgFillPrice\":\"50010\"");
    }

    @Test
    void sameKeyDifferentBodyIs409() throws Exception {
        place("api-2", market("BUY", 1));

        assertThat(place("api-2", market("BUY", 2)).statusCode()).isEqualTo(409);
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        HttpResponse<String> response =
                HttpTestClient.post(port, "/api/v1/orders", Map.of("Content-Type", "application/json"), market("BUY", 1));

        assertBadRequest(response);
    }

    @Test
    void invalidIdempotencyKeyIs400() throws Exception {
        assertBadRequest(place("a b", market("BUY", 1)));
        assertBadRequest(place("k".repeat(65), market("BUY", 1)));
    }

    @Test
    void invalidBodyIs400() throws Exception {
        assertBadRequest(place("bad-1", market("BUY", 0)));
        assertBadRequest(place("bad-2", market("HOLD", 1)));
        assertBadRequest(place("bad-3", "{\"symbol\":\"BTCUSD\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"size\":1}"));
        assertBadRequest(place("bad-4", "{not json"));
        assertBadRequest(place("bad-5", "{\"side\":\"BUY\",\"type\":\"MARKET\",\"size\":1}"));
    }

    @Test
    void orderDetailsIncludeTransitionsAndFills() throws Exception {
        String id = idOf(place("det-1", market("BUY", 1)).body());

        HttpResponse<String> response = HttpTestClient.get(port, "/api/v1/orders/" + id);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"order\":{").contains("\"transitions\":[").contains("\"fills\":[")
                .contains("\"to\":\"ACKED\"").contains("\"liquidity\":\"TAKER\"").contains("\"price\":\"50010\"");
        assertThat(HttpTestClient.get(port, "/api/v1/orders?state=FILLED").body()).contains(id);
        assertThat(HttpTestClient.get(port, "/api/v1/orders?state=CANCELLED").body()).doesNotContain(id);
        assertBadRequest(HttpTestClient.get(port, "/api/v1/orders?state=BOGUS"));
    }

    @Test
    void restingLimitOrderCanBeCancelled() throws Exception {
        String body = "{\"symbol\":\"BTCUSD\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"size\":1,\"limitPrice\":40000}";
        String id = idOf(place("lim-1", body).body());
        assertThat(HttpTestClient.get(port, "/api/v1/orders/" + id).body()).contains("\"state\":\"ACKED\"");

        HttpResponse<String> cancelled = cancel(id);
        HttpResponse<String> again = cancel(id);

        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(cancelled.body()).contains("\"state\":\"CANCELLED\"");
        assertThat(again.statusCode()).isEqualTo(409);
    }

    @Test
    void unknownOrderIs404() throws Exception {
        String unknown = "00000000-0000-0000-0000-000000000000";

        assertThat(HttpTestClient.get(port, "/api/v1/orders/" + unknown).statusCode()).isEqualTo(404);
        assertThat(cancel(unknown).statusCode()).isEqualTo(404);
    }

    @Test
    void badUuidIs400() throws Exception {
        assertBadRequest(HttpTestClient.get(port, "/api/v1/orders/not-a-uuid"));
        assertBadRequest(cancel("not-a-uuid"));
    }

    @Test
    void positionsShowUnrealizedPnl() throws Exception {
        place("pos-1", market("BUY", 2));
        quote("50500", "50520", "50510");

        HttpResponse<String> positions = HttpTestClient.get(port, "/api/v1/positions");
        HttpResponse<String> pnl = HttpTestClient.get(port, "/api/v1/pnl");

        assertThat(positions.statusCode()).isEqualTo(200);
        assertThat(positions.body()).contains("\"symbol\":\"BTCUSD\"").contains("\"venue\":\"PAPER\"")
                .contains("\"size\":2").contains("\"avgEntryPrice\":\"50010\"").contains("\"markPrice\":\"50510\"")
                .contains("\"unrealizedPnl\":\"1.000\"");
        BigDecimal fees = new BigDecimal("0.050010");
        assertThat(number(pnl.body(), "feesPaid")).isEqualByComparingTo(fees);
        assertThat(pnl.body()).contains("\"unrealizedPnl\":\"1.000\"");
        assertThat(number(pnl.body(), "netPnl")).isEqualByComparingTo(new BigDecimal("1.000").subtract(fees));
    }

    @Test
    void pnlUnrealizedNullWithoutMark() throws Exception {
        place("pos-2", market("BUY", 2));
        clock.advance(java.time.Duration.ofSeconds(11));

        HttpResponse<String> positions = HttpTestClient.get(port, "/api/v1/positions");
        HttpResponse<String> pnl = HttpTestClient.get(port, "/api/v1/pnl");

        assertThat(positions.body()).contains("\"markPrice\":null").contains("\"unrealizedPnl\":null");
        assertThat(pnl.body()).contains("\"unrealizedPnl\":null").contains("\"netPnl\":null")
                .contains("\"realizedPnl\":\"0\"");
    }

    @Test
    void hugeLimitPriceIs400() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertBadRequest(place("huge-1", limit("1e99999999"))));
    }

    @Test
    void tooManyDecimalsIs400() throws Exception {
        assertBadRequest(place("decimals-1", limit("50000.0000000000001")));
    }

    private static String limit(String price) {
        return "{\"symbol\":\"BTCUSD\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"size\":1,\"limitPrice\":" + price + "}";
    }

    private HttpResponse<String> place(String key, String body) throws Exception {
        return HttpTestClient.post(port, "/api/v1/orders",
                Map.of("Content-Type", "application/json", "Idempotency-Key", key), body);
    }

    private HttpResponse<String> cancel(String id) throws Exception {
        return HttpTestClient.post(port, "/api/v1/orders/" + id + "/cancel", Map.of(), "");
    }

    private static String market(String side, long size) {
        return "{\"symbol\":\"BTCUSD\",\"side\":\"" + side + "\",\"type\":\"MARKET\",\"size\":" + size + "}";
    }

    private void quote(String bid, String ask, String mark) {
        Instant now = clock.instant();
        quotes.put(new Quote("BTCUSD", new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(mark), now, now));
    }

    private static String idOf(String body) {
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static BigDecimal number(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).isTrue();
        return new BigDecimal(m.group(1));
    }

    private static void assertBadRequest(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"error\"");
    }
}
