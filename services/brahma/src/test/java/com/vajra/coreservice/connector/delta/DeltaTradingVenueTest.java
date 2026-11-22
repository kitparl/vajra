package com.vajra.coreservice.connector.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.connector.VenueResult;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.oms.Order;
import com.vajra.coreservice.oms.OrderSource;
import com.vajra.coreservice.oms.OrderState;
import com.vajra.coreservice.oms.OrderType;
import com.vajra.coreservice.oms.Side;
import com.vajra.coreservice.oms.TimeInForce;
import com.vajra.coreservice.testsupport.MutableClock;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

class DeltaTradingVenueTest {

    private static final String BASE = DeltaPublicClient.TESTNET_BASE_URL;
    private static final String CLIENT_ORDER_ID = "v0123456789abcdef0123456789abcde";
    private static final Instant NOW = Instant.parse("2023-11-14T22:13:20Z");

    private MockRestServiceServer server;
    private MutableClock clock;
    private DeltaRateLimitGuard guard;
    private AtomicInteger sleeps;
    private DeltaTradingVenue venue;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock(NOW);
        guard = new DeltaRateLimitGuard(clock);
        sleeps = new AtomicInteger();
        DeltaPrivateClient client = new DeltaPrivateClient(builder, guard, new DeltaCredentials("fake-key", "fake-secret"), clock);
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        when(instruments.find(any(), any())).thenReturn(Optional.of(instrument()));
        venue = new DeltaTradingVenue(client, instruments, guard, clock, millis -> {
            sleeps.incrementAndGet();
            clock.advance(Duration.ofMillis(millis));
        });
    }

    @Test
    void marketOrderClosedImmediatelyReturnsFilledWithDeltaFill() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(json(fixture("order.json")));
        expectFills("fills-single.json");

        VenueResult result = venue.place(order(OrderType.MARKET, TimeInForce.IOC, null), instrument());

        server.verify();
        assertThat(result).isEqualTo(new VenueResult.Filled(
                "123456789", "fill-1", new BigDecimal("57000.5"), new BigDecimal("0.01"), "TAKER"));
        assertThat(sleeps).hasValue(0);
    }

    @Test
    void marketOrderPendingThenClosedPollsUntilFilled() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(json(fixture("order-pending.json")));
        server.expect(requestTo(BASE + "/v2/orders/client_order_id/" + CLIENT_ORDER_ID)).andRespond(json(fixture("order-open.json")));
        server.expect(requestTo(BASE + "/v2/orders/client_order_id/" + CLIENT_ORDER_ID)).andRespond(json(fixture("order.json")));
        expectFills("fills-single.json");

        VenueResult result = venue.place(order(OrderType.MARKET, TimeInForce.IOC, null), instrument());

        server.verify();
        assertThat(result).isInstanceOf(VenueResult.Filled.class);
        assertThat(sleeps).hasValue(2);
    }

    @Test
    void marketOrderStillOpenAfter3sReturnsResting() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(json(fixture("order-open.json")));
        for (int i = 0; i < 10; i++) {
            server.expect(requestTo(BASE + "/v2/orders/client_order_id/" + CLIENT_ORDER_ID))
                    .andRespond(json(fixture("order-open.json")));
        }

        VenueResult result = venue.place(order(OrderType.MARKET, TimeInForce.IOC, null), instrument());

        server.verify();
        assertThat(result).isEqualTo(new VenueResult.Resting("123456789"));
        assertThat(sleeps).hasValue(10);
    }

    @Test
    void limitGtcOpenReturnsResting() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(json(fixture("order-open.json")));

        VenueResult result = venue.place(order(OrderType.LIMIT, TimeInForce.GTC, "57000.5"), instrument());

        server.verify();
        assertThat(result).isEqualTo(new VenueResult.Resting("123456789"));
        assertThat(sleeps).hasValue(0);
    }

    @Test
    void iocCancelledReturnsExpired() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(json(fixture("order-cancelled.json")));

        VenueResult result = venue.place(order(OrderType.LIMIT, TimeInForce.IOC, "57000.5"), instrument());

        assertThat(result).isEqualTo(new VenueResult.Expired("123456789"));
    }

    @Test
    void rateLimitedPlaceDoesNotCallDelta() {
        guard.pauseFor(Duration.ofSeconds(30));

        VenueResult result = venue.place(order(OrderType.MARKET, TimeInForce.IOC, null), instrument());

        assertThat(result).isEqualTo(new VenueResult.Rejected("RATE_LIMITED"));
        server.verify();
    }

    @Test
    void nonStandardErrorBodiesMapToRejected() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(
                withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"SignatureExpired\",\"message\":\"expired\"}"));
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(
                withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_PLAIN).body("Request blocked by CDN"));
        Order order = order(OrderType.MARKET, TimeInForce.IOC, null);

        assertThat(venue.place(order, instrument())).isEqualTo(new VenueResult.Rejected("SignatureExpired"));
        assertThat(venue.place(order, instrument())).isEqualTo(new VenueResult.Rejected("HTTP_403"));
    }

    @Test
    void serverErrorOnPlaceIsRethrown() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> venue.place(order(OrderType.MARKET, TimeInForce.IOC, null), instrument()))
                .isInstanceOfSatisfying(DeltaApiException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void requestBodyMatchesDeltaFormat() {
        AtomicReference<String> sent = new AtomicReference<>();
        server.expect(requestTo(BASE + "/v2/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> sent.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(json(fixture("order-open.json")));

        venue.place(order(OrderType.LIMIT, TimeInForce.GTC, "57000.50"), instrument());

        assertThat(sent.get()).isEqualTo("{\"product_id\":27,\"size\":2,\"side\":\"buy\",\"order_type\":\"limit_order\","
                + "\"limit_price\":\"57000.50\",\"time_in_force\":\"gtc\",\"reduce_only\":false,"
                + "\"client_order_id\":\"" + CLIENT_ORDER_ID + "\"}");
    }

    @Test
    void cancelCancelledReturnsCancelledAndClosedReturnsFilled() {
        server.expect(requestTo(BASE + "/v2/orders")).andExpect(method(HttpMethod.DELETE))
                .andExpect(request -> assertThat(((MockClientHttpRequest) request).getBodyAsString())
                        .contains("\"id\":123456789", "\"product_id\":27"))
                .andRespond(json(fixture("order-cancelled.json")));
        server.expect(requestTo(BASE + "/v2/orders")).andExpect(method(HttpMethod.DELETE))
                .andRespond(json(fixture("order.json")));
        expectFills("fills-single.json");
        Order resting = withVenueOrderId(order(OrderType.LIMIT, TimeInForce.GTC, "57000.5"), "123456789");

        assertThat(venue.cancel(resting)).isEqualTo(new VenueResult.Cancelled("123456789"));
        assertThat(venue.cancel(resting)).isInstanceOf(VenueResult.Filled.class);
    }

    @Test
    void checkRestingOpenIsEmptyAndClosedIsFilledAndUnknownIdIsEmpty() {
        String path = BASE + "/v2/orders/client_order_id/" + CLIENT_ORDER_ID;
        server.expect(requestTo(path)).andRespond(json(fixture("order-open.json")));
        server.expect(requestTo(path)).andRespond(json(fixture("order.json")));
        expectFills("fills-single.json");
        server.expect(requestTo(path)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        Order order = order(OrderType.LIMIT, TimeInForce.GTC, "57000.5");

        assertThat(venue.checkResting(order, instrument())).isEmpty();
        assertThat(venue.checkResting(order, instrument())).containsInstanceOf(VenueResult.Filled.class);
        assertThat(venue.resolve(order, instrument())).isEmpty();
    }

    @Test
    void resolveReportsOpenOrderAsResting() {
        server.expect(requestTo(BASE + "/v2/orders/client_order_id/" + CLIENT_ORDER_ID))
                .andRespond(json(fixture("order-open.json")));

        assertThat(venue.resolve(order(OrderType.LIMIT, TimeInForce.GTC, "57000.5"), instrument()))
                .contains(new VenueResult.Resting("123456789"));
    }

    @Test
    void closedButFillsNotVisibleDuringPlaceReturnsResting() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(json(fixture("order.json")));
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v2/fills"))
                .andRespond(json("{\"success\":true,\"result\":[],\"meta\":{}}"));

        assertThat(venue.place(order(OrderType.MARKET, TimeInForce.IOC, null), instrument()))
                .isEqualTo(new VenueResult.Resting("123456789"));
    }

    @Test
    void closedButFillsNotVisibleInCheckerPathIsRetryable() {
        server.expect(requestTo(BASE + "/v2/orders/client_order_id/" + CLIENT_ORDER_ID)).andRespond(json(fixture("order.json")));
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v2/fills"))
                .andRespond(json("{\"success\":true,\"result\":[],\"meta\":{}}"));

        assertThatThrownBy(() -> venue.checkResting(order(OrderType.LIMIT, TimeInForce.GTC, "57000.5"), instrument()))
                .isInstanceOfSatisfying(DeltaApiException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.code()).isEqualTo("FILL_PENDING");
                });
    }

    @Test
    void closedWithOnlySomeFillsVisibleIsPending() {
        String path = BASE + "/v2/orders/client_order_id/" + CLIENT_ORDER_ID;
        server.expect(requestTo(path)).andRespond(json(fixture("order.json")));
        expectFills("fills-one-of-two.json");
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(json(fixture("order.json")));
        expectFills("fills-one-of-two.json");
        Order order = order(OrderType.LIMIT, TimeInForce.GTC, "57000.5");

        assertThatThrownBy(() -> venue.checkResting(order, instrument()))
                .isInstanceOfSatisfying(DeltaApiException.class, e -> assertThat(e.code()).isEqualTo("FILL_PENDING"));
        assertThat(venue.place(order, instrument())).isEqualTo(new VenueResult.Resting("123456789"));
    }

    @Test
    void cancelledWithPartialFillIsNeverMappedToCancelled() {
        server.expect(requestTo(BASE + "/v2/orders/client_order_id/" + CLIENT_ORDER_ID))
                .andRespond(json(fixture("order-cancelled-partial.json")));
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(json(fixture("order-cancelled-partial.json")));
        Order order = order(OrderType.LIMIT, TimeInForce.GTC, "57000.5");

        assertThatThrownBy(() -> venue.checkResting(order, instrument()))
                .isInstanceOfSatisfying(DeltaApiException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.code()).isEqualTo("PARTIAL_FILL_UNSUPPORTED");
                });
        assertThat(venue.place(order, instrument())).isEqualTo(new VenueResult.Resting("123456789"));
    }

    @Test
    void pausedGuardDuringPollAfterAcceptReturnsResting() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(request -> {
            guard.pauseFor(Duration.ofSeconds(30));
            return json(fixture("order-pending.json")).createResponse(request);
        });

        assertThat(venue.place(order(OrderType.MARKET, TimeInForce.IOC, null), instrument()))
                .isEqualTo(new VenueResult.Resting("123456789"));
    }

    @Test
    void rateLimit429OnPollAfterAcceptReturnsResting() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(json(fixture("order-pending.json")));
        server.expect(requestTo(BASE + "/v2/orders/client_order_id/" + CLIENT_ORDER_ID))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(venue.place(order(OrderType.MARKET, TimeInForce.IOC, null), instrument()))
                .isEqualTo(new VenueResult.Resting("123456789"));
    }

    @Test
    void fillsLookupFailureAfterClosedReturnsResting() {
        server.expect(requestTo(BASE + "/v2/orders")).andRespond(json(fixture("order.json")));
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v2/fills"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_PLAIN).body("blocked"));

        assertThat(venue.place(order(OrderType.MARKET, TimeInForce.IOC, null), instrument()))
                .isEqualTo(new VenueResult.Resting("123456789"));
    }

    @Test
    void cancelWhilePausedStillCallsDelta() {
        guard.pauseFor(Duration.ofSeconds(30));
        server.expect(requestTo(BASE + "/v2/orders")).andExpect(method(HttpMethod.DELETE))
                .andRespond(json(fixture("order-cancelled.json")));

        VenueResult result = venue.cancel(withVenueOrderId(order(OrderType.LIMIT, TimeInForce.GTC, "57000.5"), "123456789"));

        server.verify();
        assertThat(result).isEqualTo(new VenueResult.Cancelled("123456789"));
    }

    private void expectFills(String fixture) {
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v2/fills"))
                .andRespond(json(fixture(fixture)));
    }

    private static Instrument instrument() {
        return new Instrument(Venue.DELTA_TESTNET, "BTCUSD", 27, "perpetual_futures", new BigDecimal("0.001"),
                new BigDecimal("0.5"), BigDecimal.ONE, new BigDecimal("0.0002"), new BigDecimal("0.0005"), true, NOW);
    }

    private static Order order(OrderType type, TimeInForce tif, String limitPrice) {
        return new Order(UUID.randomUUID(), CLIENT_ORDER_ID, "key", Venue.DELTA_TESTNET, 1, "BTCUSD", Side.BUY, type, 2,
                limitPrice == null ? null : new BigDecimal(limitPrice), tif, false, OrderState.SENT, 0, null,
                BigDecimal.ZERO, null, OrderSource.MANUAL, null, NOW, NOW, 0);
    }

    private static Order withVenueOrderId(Order o, String venueOrderId) {
        return new Order(o.id(), o.clientOrderId(), o.idempotencyKey(), o.venue(), o.instrumentId(), o.symbol(), o.side(),
                o.type(), o.size(), o.limitPrice(), o.timeInForce(), o.reduceOnly(), o.state(), o.filledSize(),
                o.avgFillPrice(), o.fees(), venueOrderId, o.source(), o.reason(), o.createdAt(), o.updatedAt(), o.version());
    }

    private static ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    private static String fixture(String name) {
        try (InputStream in = DeltaTradingVenueTest.class.getResourceAsStream("/delta/private/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
