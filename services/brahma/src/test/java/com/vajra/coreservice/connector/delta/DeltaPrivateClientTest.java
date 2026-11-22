package com.vajra.coreservice.connector.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.vajra.coreservice.testsupport.MutableClock;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class DeltaPrivateClientTest {

    private static final String BASE = DeltaPublicClient.TESTNET_BASE_URL;
    private static final String FAKE_KEY = "fake-test-key-0001";
    private static final String FAKE_SECRET = "fake-test-secret-0002";
    private static final String CLIENT_ORDER_ID = "v0123456789abcdef0123456789abcde";

    private MockRestServiceServer server;
    private MutableClock clock;
    private DeltaRateLimitGuard guard;
    private DeltaPrivateClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock(Instant.parse("2023-11-14T22:13:20Z"));
        guard = new DeltaRateLimitGuard(clock);
        client = new DeltaPrivateClient(builder, guard, new DeltaCredentials(FAKE_KEY, FAKE_SECRET), clock);
    }

    private static DeltaPrivateClient.PlaceOrderBody marketBuy() {
        return new DeltaPrivateClient.PlaceOrderBody(27, 1, "buy", "market_order", null, "ioc", false, CLIENT_ORDER_ID);
    }

    @Test
    void placeOrderSendsSignedHeadersAndParsesOrder() {
        AtomicReference<String> sentBody = new AtomicReference<>();
        server.expect(requestTo(BASE + "/v2/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", FAKE_KEY))
                .andExpect(header("timestamp", "1700000000"))
                .andExpect(header(HttpHeaders.USER_AGENT, "vajra"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(request -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    sentBody.set(body);
                    String expected = DeltaSigner.sign(FAKE_SECRET, "POST", 1700000000L, "/v2/orders", "", body);
                    assertThat(request.getHeaders().getFirst("signature")).isEqualTo(expected);
                })
                .andRespond(json(fixture("order.json")));

        DeltaOrder order = client.placeOrder(marketBuy());

        server.verify();
        assertThat(sentBody.get())
                .contains("\"product_id\":27", "\"size\":1", "\"side\":\"buy\"", "\"order_type\":\"market_order\"")
                .contains("\"time_in_force\":\"ioc\"", "\"reduce_only\":false", "\"client_order_id\":\"" + CLIENT_ORDER_ID + "\"")
                .doesNotContain("limit_price");
        assertThat(order.id()).isEqualTo(123456789L);
        assertThat(order.clientOrderId()).isEqualTo(CLIENT_ORDER_ID);
        assertThat(order.state()).isEqualTo("closed");
        assertThat(order.size()).isEqualTo(2);
        assertThat(order.unfilledSize()).isZero();
        assertThat(order.averageFillPrice()).isEqualByComparingTo("57000.5");
        assertThat(order.paidCommission()).isEqualByComparingTo("0.0285");
        assertThat(order.productId()).isEqualTo(27);
    }

    @Test
    void limitPriceIsSentAsString() {
        server.expect(requestTo(BASE + "/v2/orders"))
                .andExpect(request -> assertThat(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString())
                        .contains("\"limit_price\":\"57000.5\""))
                .andRespond(json(fixture("order.json")));

        client.placeOrder(new DeltaPrivateClient.PlaceOrderBody(27, 1, "buy", "limit_order", "57000.5", "gtc", false, CLIENT_ORDER_ID));

        server.verify();
    }

    @Test
    void cancelOrderSendsSignedDeleteWithBody() {
        server.expect(requestTo(BASE + "/v2/orders"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(request -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    assertThat(body).contains("\"id\":123456789", "\"product_id\":27");
                    assertThat(request.getHeaders().getFirst("signature"))
                            .isEqualTo(DeltaSigner.sign(FAKE_SECRET, "DELETE", 1700000000L, "/v2/orders", "", body));
                })
                .andRespond(json(fixture("order.json")));

        DeltaOrder order = client.cancelOrder(123456789L, 27);

        server.verify();
        assertThat(order.id()).isEqualTo(123456789L);
    }

    @Test
    void orderByClientIdFindsOrder() {
        server.expect(requestTo(BASE + "/v2/orders/client_order_id/" + CLIENT_ORDER_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(json(fixture("order.json")));

        Optional<DeltaOrder> order = client.orderByClientId(CLIENT_ORDER_ID);

        assertThat(order).get().extracting(DeltaOrder::id).isEqualTo(123456789L);
    }

    @Test
    void orderByClientIdNotFoundIsEmpty() {
        server.expect(requestTo(BASE + "/v2/orders/client_order_id/" + CLIENT_ORDER_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"error\":{\"code\":\"order_not_found\"}}"));
        server.expect(requestTo(BASE + "/v2/orders/client_order_id/" + CLIENT_ORDER_ID))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"error\":{\"code\":\"order_not_found\"}}"));

        assertThat(client.orderByClientId(CLIENT_ORDER_ID)).isEmpty();
        assertThat(client.orderByClientId(CLIENT_ORDER_ID)).isEmpty();
    }

    @Test
    void fillsFollowsCursorAcrossPages() {
        long startMicros = Instant.parse("2026-11-22T00:00:00Z").getEpochSecond() * 1_000_000;
        String first = BASE + "/v2/fills?start_time=" + startMicros + "&page_size=100";
        server.expect(requestTo(first))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("signature")).isEqualTo(DeltaSigner.sign(
                        FAKE_SECRET, "GET", 1700000000L, "/v2/fills", "?start_time=" + startMicros + "&page_size=100", "")))
                .andRespond(json(fixture("fills-page1.json")));
        server.expect(requestTo(first + "&after=cursor-2"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("signature")).isEqualTo(DeltaSigner.sign(
                        FAKE_SECRET, "GET", 1700000000L, "/v2/fills", "?start_time=" + startMicros + "&page_size=100&after=cursor-2", "")))
                .andRespond(json(fixture("fills-page2.json")));

        List<DeltaFill> fills = client.fills(Instant.parse("2026-11-22T00:00:00Z"));

        server.verify();
        assertThat(fills).extracting(DeltaFill::id).containsExactly("fill-1", "fill-2");
        DeltaFill one = fills.get(0);
        assertThat(one.orderId()).isEqualTo(123456789L);
        assertThat(one.size()).isEqualTo(1);
        assertThat(one.price()).isEqualByComparingTo("57000.5");
        assertThat(one.commission()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(one.role()).isEqualTo("taker");
        assertThat(one.createdAt()).isEqualTo(Instant.parse("2026-11-22T10:00:00.123456Z"));
        DeltaFill two = fills.get(1);
        assertThat(two.orderId()).isEqualTo(123456789L);
        assertThat(two.createdAt()).isEqualTo(Instant.ofEpochSecond(1763805600L, 123_456_000));
    }

    @Test
    void positionMapsSignedSizeAndEntryPrice() {
        server.expect(requestTo(BASE + "/v2/positions?product_id=27"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(json(fixture("position.json")));

        DeltaPosition position = client.position(27);

        assertThat(position.size()).isEqualTo(-3);
        assertThat(position.entryPrice()).isEqualByComparingTo("56999.25");
    }

    @Test
    void authCheckCallsWalletBalances() {
        server.expect(requestTo(BASE + "/v2/wallet/balances"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(json("{\"success\":true,\"result\":[]}"));

        client.authCheck();

        server.verify();
    }

    @Test
    void rateLimit429PausesGuard() {
        server.expect(requestTo(BASE + "/v2/positions?product_id=27"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("X-RATE-LIMIT-RESET", "4000"));
        server.expect(requestTo(BASE + "/v2/positions?product_id=27")).andRespond(json(fixture("position.json")));

        assertThatThrownBy(() -> client.position(27))
                .isInstanceOfSatisfying(DeltaApiException.class, e -> {
                    assertThat(e.rateLimited()).isTrue();
                    assertThat(e.status()).isEqualTo(429);
                });

        assertThatThrownBy(() -> client.position(27)).isInstanceOfSatisfying(DeltaApiException.class,
                e -> assertThat(e.rateLimited()).isTrue());
        clock.advance(Duration.ofSeconds(5));
        assertThat(client.position(27).size()).isEqualTo(-3);
    }

    @Test
    void deltaErrorBodyMapsToCode() {
        server.expect(requestTo(BASE + "/v2/orders"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(fixture("error-insufficient-margin.json")));

        assertThatThrownBy(() -> client.placeOrder(marketBuy())).isInstanceOfSatisfying(DeltaApiException.class, e -> {
            assertThat(e.code()).isEqualTo("insufficient_margin");
            assertThat(e.retryable()).isFalse();
            assertThat(e.status()).isEqualTo(400);
        });
    }

    @Test
    void nonStandardErrorBodyMapsToItsErrorString() {
        server.expect(requestTo(BASE + "/v2/orders"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"SignatureExpired\",\"message\":\"Signature expired\"}"));

        assertThatThrownBy(() -> client.placeOrder(marketBuy()))
                .isInstanceOfSatisfying(DeltaApiException.class, e -> assertThat(e.code()).isEqualTo("SignatureExpired"));
    }

    @Test
    void nonJsonErrorBodyMapsToHttpStatusCode() {
        server.expect(requestTo(BASE + "/v2/orders"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_PLAIN).body("Request blocked by CDN"));

        assertThatThrownBy(() -> client.placeOrder(marketBuy())).isInstanceOfSatisfying(DeltaApiException.class, e -> {
            assertThat(e.code()).isEqualTo("HTTP_403");
            assertThat(e.retryable()).isFalse();
        });
    }

    @Test
    void serverErrorIsRetryable() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("upstream down"));

        assertThatThrownBy(() -> client.placeOrder(marketBuy())).isInstanceOfSatisfying(DeltaApiException.class, e -> {
            assertThat(e.code()).isEqualTo("HTTP_502");
            assertThat(e.retryable()).isTrue();
        });
    }

    @Test
    void ioErrorIsRetryable() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(request -> {
            throw new IOException("connection reset");
        });

        assertThatThrownBy(() -> client.placeOrder(marketBuy())).isInstanceOfSatisfying(DeltaApiException.class,
                e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void secretNeverInExceptionOrLog(CapturedOutput output) {
        List<ResponseCreator> failures = List.of(
                withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(fixture("error-insufficient-margin.json")),
                withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_PLAIN)
                        .body("blocked " + FAKE_KEY + " " + FAKE_SECRET),
                withStatus(HttpStatus.TOO_MANY_REQUESTS).header("api-key", FAKE_KEY).header("signature", FAKE_SECRET),
                withStatus(HttpStatus.INTERNAL_SERVER_ERROR).header("api-key", FAKE_KEY).body(FAKE_SECRET),
                request -> {
                    throw new IOException("reset " + FAKE_KEY);
                });
        for (ResponseCreator failure : failures) {
            clock.advance(Duration.ofMinutes(2));
            server.reset();
            server.expect(requestTo(BASE + "/v2/orders")).andRespond(failure);
            Throwable thrown = null;
            try {
                client.placeOrder(marketBuy());
            } catch (DeltaApiException e) {
                thrown = e;
            }
            assertThat(thrown).isNotNull();
            assertThat(thrown.getMessage()).doesNotContain(FAKE_SECRET, FAKE_KEY);
            assertThat(thrown.toString()).doesNotContain(FAKE_SECRET, FAKE_KEY);
            assertThat(thrown.getCause()).isNull();
        }
        assertThat(output.getAll()).doesNotContain(FAKE_SECRET, FAKE_KEY);
        assertThat(new DeltaCredentials(FAKE_KEY, FAKE_SECRET).toString())
                .isEqualTo("DeltaCredentials[apiKey=***, apiSecret=***]");
    }

    @Test
    void malformedSuccessBodyIsRetryableBadBody() {
        String badPrice = fixture("order.json").replace("\"57000.5\"", "\"not-a-number\"");
        String badTime = fixture("fills-page1.json").replace("2026-11-22T10:00:00.123456Z", "yesterday");
        String noId = "{\"success\":true,\"result\":{}}";
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(json(badPrice));
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(json(noId));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/v2/fills"))).andRespond(json(badTime));
        server.expect(requestTo(BASE + "/v2/positions?product_id=27"))
                .andRespond(json("{\"success\":true,\"result\":{\"size\":1,\"entry_price\":\"x\"}}"));

        List<Runnable> calls = List.of(
                () -> client.placeOrder(marketBuy()),
                () -> client.placeOrder(marketBuy()),
                () -> client.fills(Instant.parse("2026-11-22T00:00:00Z")),
                () -> client.position(27));
        for (Runnable call : calls) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(DeltaApiException.class, e -> {
                assertThat(e.code()).isEqualTo("BAD_BODY");
                assertThat(e.retryable()).isTrue();
                assertThat(e.getCause()).isNull();
                assertThat(e.getMessage()).doesNotContain("not-a-number", "yesterday");
            });
        }
    }

    @Test
    void credentialWithControlCharIsRejectedWithoutEchoingIt() {
        DeltaCredentials bad = new DeltaCredentials("bad\nkey", FAKE_SECRET);
        DeltaCredentials padded = new DeltaCredentials("  " + FAKE_KEY + " \n", " " + FAKE_SECRET + " ");

        assertThat(bad.isPresent()).isFalse();
        assertThat(padded.isPresent()).isTrue();
        assertThat(padded.apiKey()).isEqualTo(FAKE_KEY);
        DeltaPrivateClient badClient = new DeltaPrivateClient(RestClient.builder(), guard, bad, clock);
        assertThatThrownBy(badClient::authCheck).isInstanceOfSatisfying(DeltaApiException.class, e -> {
            assertThat(e.code()).isEqualTo("NO_CREDENTIALS");
            assertThat(e.getMessage()).doesNotContain("bad", FAKE_SECRET);
        });
    }

    @Test
    void unexpectedRuntimeExceptionIsWrappedWithoutMessage() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(request -> {
            throw new IllegalArgumentException("invalid header value " + FAKE_KEY);
        });

        assertThatThrownBy(() -> client.placeOrder(marketBuy())).isInstanceOfSatisfying(DeltaApiException.class, e -> {
            assertThat(e.code()).isEqualTo("CLIENT_ERROR");
            assertThat(e.retryable()).isTrue();
            assertThat(e.getCause()).isNull();
            assertThat(e.getMessage()).isEqualTo("Delta client error: IllegalArgumentException");
        });
    }

    @Test
    void credentialsPresenceNeedsBothValues() {
        assertThat(new DeltaCredentials("k", "s").isPresent()).isTrue();
        assertThat(new DeltaCredentials("k", " ").isPresent()).isFalse();
        assertThat(new DeltaCredentials("", "s").isPresent()).isFalse();
        assertThat(new DeltaCredentials(null, null).isPresent()).isFalse();
    }

    private static ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    private static String fixture(String name) {
        try (InputStream in = DeltaPrivateClientTest.class.getResourceAsStream("/delta/private/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
