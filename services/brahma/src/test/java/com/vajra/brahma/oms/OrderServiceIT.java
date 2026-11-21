package com.vajra.brahma.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.vajra.brahma.api.ApiException;
import com.vajra.brahma.connector.Quote;
import com.vajra.brahma.connector.TradingVenue;
import com.vajra.brahma.connector.Venue;
import com.vajra.brahma.instrument.Instrument;
import com.vajra.brahma.instrument.InstrumentRepository;
import com.vajra.brahma.marketdata.QuoteCache;
import com.vajra.brahma.position.Position;
import com.vajra.brahma.position.PositionRepository;
import com.vajra.brahma.risk.TradingStateRepository;
import com.vajra.brahma.testsupport.ApprovingRiskGate;
import com.vajra.brahma.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = "vajra.scheduling.enabled=false")
@Import(OrderServiceIT.RiskOverride.class)
class OrderServiceIT {

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
        registry.add("RISK_API_TOKEN", () -> "test-risk-token");
        registry.add("CORE_INTERNAL_TOKEN", () -> "test-internal-token");
    }

    @Autowired
    OrderService orderService;

    @Autowired
    PaperMatcher matcher;

    @Autowired
    InstrumentRepository instruments;

    @Autowired
    PositionRepository positions;

    @Autowired
    QuoteCache quotes;

    @Autowired
    Clock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApprovingRiskGate riskGate;

    @Autowired
    TradingStateRepository tradingState;

    long btcId;

    @BeforeEach
    void resetData() {
        for (String table : List.of("audit_event", "fill", "order_transition", "orders", "position")) {
            jdbc.sql("delete from " + table).update();
        }
        Instant now = clock.instant();
        tradingState.setHalted(false, null, now);
        riskGate.reset();
        instruments.upsertAll(List.of(
                instrument("BTCUSD", true, now),
                instrument("XRPUSD", false, now)));
        btcId = instruments.idOf(Venue.DELTA_TESTNET, "BTCUSD").orElseThrow();
        quote("50000", "50010", now);
    }

    @Test
    void marketBuyFillsAndUpdatesPosition() {
        PlaceOrderResult result = orderService.place(market("mkt-1", Side.BUY, 2));

        assertThat(result.created()).isTrue();
        Order order = result.order();
        assertThat(order.state()).isEqualTo(OrderState.FILLED);
        assertThat(order.venue()).isEqualTo(Venue.PAPER);
        assertThat(order.instrumentId()).isEqualTo(btcId);
        assertThat(order.clientOrderId()).isEqualTo(ClientOrderIds.fromKey("mkt-1"));
        assertThat(order.timeInForce()).isEqualTo(TimeInForce.IOC);
        assertThat(order.filledSize()).isEqualTo(2);
        assertThat(order.avgFillPrice()).isEqualByComparingTo("50010");
        assertThat(order.fees()).isEqualByComparingTo("0.050010");
        assertThat(states(order.id())).containsExactly(
                OrderState.NEW, OrderState.APPROVED, OrderState.SENT, OrderState.ACKED, OrderState.FILLED);
        assertThat(fills(order.id())).hasSize(1);
        Position position = positions.find(Venue.PAPER, btcId).orElseThrow();
        assertThat(position.size()).isEqualTo(2);
        assertThat(position.avgEntryPrice()).isEqualByComparingTo("50010");
        assertThat(position.feesPaid()).isEqualByComparingTo("0.050010");
        assertThat(auditCount("order.updated")).isEqualTo(5);
        assertThat(auditCount("fill.created")).isEqualTo(1);
        assertThat(auditCount("position.updated")).isEqualTo(1);
    }

    @Test
    void riskDecisionIdIsStored() {
        Order order = orderService.place(market("rid-1", Side.BUY, 1)).order();

        assertThat(orderService.details(order.id()).orElseThrow().riskDecisionId())
                .isEqualTo(ApprovingRiskGate.DECISION_ID);
        assertThat(riskGate.calls()).isEqualTo(1);
    }

    @Test
    void localHaltRejectsWithoutCallingRisk() {
        tradingState.setHalted(true, "kill switch", clock.instant());

        Order order = orderService.place(market("halt-1", Side.BUY, 1)).order();

        assertThat(order.state()).isEqualTo(OrderState.RISK_REJECTED);
        assertThat(order.reason()).isEqualTo("HALTED");
        assertThat(states(order.id())).containsExactly(OrderState.NEW, OrderState.RISK_REJECTED);
        assertThat(orderService.details(order.id()).orElseThrow().riskDecisionId()).isNull();
        assertThat(riskGate.calls()).isZero();
        assertThat(fills(order.id())).isEmpty();
    }

    @Test
    void localHaltRejectsReduceOnlyManualOrder() {
        orderService.place(market("halt-open", Side.BUY, 2));
        tradingState.setHalted(true, "kill switch", clock.instant());

        Order order = orderService.place(new OrderIntent("halt-ro", "BTCUSD", Side.SELL, OrderType.MARKET, 1, null,
                null, true, OrderSource.MANUAL)).order();

        assertThat(order.state()).isEqualTo(OrderState.RISK_REJECTED);
        assertThat(order.reason()).isEqualTo("HALTED");
    }

    @Test
    void killSwitchFlattenPassesLocalHalt() {
        orderService.place(market("flat-open", Side.BUY, 2));
        tradingState.setHalted(true, "kill switch", clock.instant());

        Order order = orderService.place(new OrderIntent("flat-1", "BTCUSD", Side.SELL, OrderType.MARKET, 2, null,
                null, true, OrderSource.KILL_SWITCH)).order();

        assertThat(order.state()).isEqualTo(OrderState.FILLED);
        assertThat(order.source()).isEqualTo(OrderSource.KILL_SWITCH);
        assertThat(riskGate.calls()).isEqualTo(2);
        assertThat(positions.find(Venue.PAPER, btcId).orElseThrow().size()).isZero();
    }

    @Test
    void sameKeyReturnsSameOrder() {
        PlaceOrderResult first = orderService.place(market("same-1", Side.BUY, 1));
        PlaceOrderResult second = orderService.place(market("same-1", Side.BUY, 1));

        assertThat(second.created()).isFalse();
        assertThat(second.order().id()).isEqualTo(first.order().id());
        assertThat(second.order().state()).isEqualTo(OrderState.FILLED);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(fills(first.order().id())).hasSize(1);
    }

    @Test
    void sameKeyDifferentBodyIsConflict() {
        orderService.place(market("diff-1", Side.BUY, 1));

        assertThatThrownBy(() -> orderService.place(market("diff-1", Side.BUY, 3)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateKeysCreateOneOrder() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Callable<UUID> task = () -> {
                start.await();
                return orderService.place(market("race-1", Side.BUY, 1)).order().id();
            };
            List<Future<UUID>> futures = IntStream.range(0, threads).mapToObj(i -> pool.submit(task)).toList();
            start.countDown();

            List<UUID> ids = futures.stream().map(OrderServiceIT::await).distinct().toList();

            assertThat(ids).hasSize(1);
            assertThat(orderCount()).isEqualTo(1);
            assertThat(fills(ids.get(0))).hasSize(1);
            assertThat(positions.find(Venue.PAPER, btcId).orElseThrow().size()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleQuoteRejectsOrder() {
        quote("50000", "50010", clock.instant().minus(Duration.ofSeconds(60)));

        Order order = orderService.place(market("stale-1", Side.BUY, 1)).order();

        assertThat(order.state()).isEqualTo(OrderState.REJECTED);
        assertThat(order.reason()).isEqualTo("STALE_PRICE");
        assertThat(states(order.id())).containsExactly(
                OrderState.NEW, OrderState.APPROVED, OrderState.SENT, OrderState.REJECTED);
        assertThat(fills(order.id())).isEmpty();
        assertThat(positions.find(Venue.PAPER, btcId)).isEmpty();
    }

    @Test
    void disabledInstrumentIsBadRequest() {
        assertBadRequest(new OrderIntent("dis-1", "XRPUSD", Side.BUY, OrderType.MARKET, 1, null, null, false,
                OrderSource.MANUAL));
        assertBadRequest(new OrderIntent("dis-2", "NOPEUSD", Side.BUY, OrderType.MARKET, 1, null, null, false,
                OrderSource.MANUAL));
        assertThat(orderCount()).isZero();
    }

    @Test
    void limitPriceOffTickIsBadRequest() {
        assertBadRequest(limit("tick-1", Side.BUY, 1, "50000.05", TimeInForce.GTC));
        assertBadRequest(limit("tick-2", Side.BUY, 1, null, TimeInForce.GTC));
        assertBadRequest(limit("tick-3", Side.BUY, 1, "0", TimeInForce.GTC));
        assertBadRequest(limit("tick-4", Side.BUY, 0, "50000", TimeInForce.GTC));
        assertBadRequest(new OrderIntent("tick-5", "BTCUSD", Side.BUY, OrderType.MARKET, 1, new BigDecimal("50000"),
                null, false, OrderSource.MANUAL));
        assertBadRequest(new OrderIntent("tick-6", "BTCUSD", Side.BUY, OrderType.MARKET, 1, null, TimeInForce.GTC,
                false, OrderSource.MANUAL));
        assertBadRequest(new OrderIntent("bad key!", "BTCUSD", Side.BUY, OrderType.MARKET, 1, null, null, false,
                OrderSource.MANUAL));
        assertThat(orderCount()).isZero();
    }

    @Test
    void limitWithoutTimeInForceDefaultsToGtc() {
        Order order = orderService.place(limit("gtc-1", Side.BUY, 1, "49900", null)).order();

        assertThat(order.timeInForce()).isEqualTo(TimeInForce.GTC);
        assertThat(order.state()).isEqualTo(OrderState.ACKED);
    }

    @Test
    void reduceOnlyCannotIncrease() {
        Order order = orderService.place(new OrderIntent("ro-1", "BTCUSD", Side.SELL, OrderType.MARKET, 1, null, null,
                true, OrderSource.MANUAL)).order();

        assertThat(order.state()).isEqualTo(OrderState.REJECTED);
        assertThat(order.reason()).isEqualTo("REDUCE_ONLY_WOULD_INCREASE");
        assertThat(states(order.id())).containsExactly(
                OrderState.NEW, OrderState.APPROVED, OrderState.SENT, OrderState.REJECTED);
        assertThat(fills(order.id())).isEmpty();
    }

    @Test
    void reduceOnlyMayReduce() {
        orderService.place(market("ro-open", Side.BUY, 2));

        Order order = orderService.place(new OrderIntent("ro-close", "BTCUSD", Side.SELL, OrderType.MARKET, 1, null,
                null, true, OrderSource.MANUAL)).order();

        assertThat(order.state()).isEqualTo(OrderState.FILLED);
        assertThat(positions.find(Venue.PAPER, btcId).orElseThrow().size()).isEqualTo(1);
    }

    @Test
    void restingReduceOnlyDoesNotFlipOnFill() {
        orderService.place(market("rof-open", Side.BUY, 1));
        Order resting = orderService.place(new OrderIntent("rof-rest", "BTCUSD", Side.SELL, OrderType.LIMIT, 1,
                new BigDecimal("51000"), TimeInForce.GTC, true, OrderSource.MANUAL)).order();
        assertThat(resting.state()).isEqualTo(OrderState.ACKED);
        orderService.place(market("rof-close", Side.SELL, 1));
        assertThat(positions.find(Venue.PAPER, btcId).orElseThrow().size()).isZero();

        quote("51100", "51110", clock.instant());
        matcher.run();

        Order after = orderService.details(resting.id()).orElseThrow().order();
        assertThat(after.state()).isEqualTo(OrderState.CANCELLED);
        assertThat(after.reason()).isEqualTo("REDUCE_ONLY_WOULD_INCREASE");
        assertThat(states(resting.id())).endsWith(OrderState.ACKED, OrderState.CANCEL_REQUESTED, OrderState.CANCELLED);
        assertThat(fills(resting.id())).isEmpty();
        assertThat(positions.find(Venue.PAPER, btcId).orElseThrow().size()).isZero();
    }

    @Test
    void restingLimitFillsWhenMatcherRuns() {
        Order resting = orderService.place(limit("rest-1", Side.BUY, 1, "49900", TimeInForce.GTC)).order();
        assertThat(resting.state()).isEqualTo(OrderState.ACKED);
        assertThat(fills(resting.id())).isEmpty();

        quote("49880", "49890", clock.instant());
        matcher.run();

        Order filled = orderService.details(resting.id()).orElseThrow().order();
        assertThat(filled.state()).isEqualTo(OrderState.FILLED);
        assertThat(filled.avgFillPrice()).isEqualByComparingTo("49900");
        List<Fill> fills = fills(resting.id());
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).liquidity()).isEqualTo("MAKER");
        assertThat(fills.get(0).fee()).isEqualByComparingTo("0.00998");
        assertThat(states(resting.id())).containsExactly(OrderState.NEW, OrderState.APPROVED, OrderState.SENT,
                OrderState.ACKED, OrderState.FILLED);
        assertThat(positions.find(Venue.PAPER, btcId).orElseThrow().size()).isEqualTo(1);
    }

    @Test
    void staleRestingCheckDoesNotFillTwice() {
        Order resting = orderService.place(limit("twice-1", Side.BUY, 1, "49900", TimeInForce.GTC)).order();
        quote("49880", "49890", clock.instant());
        matcher.run();

        orderService.onRestingCheck(resting);

        assertThat(fills(resting.id())).hasSize(1);
        assertThat(positions.find(Venue.PAPER, btcId).orElseThrow().size()).isEqualTo(1);
        assertThat(auditCount("fill.created")).isEqualTo(1);
    }

    @Test
    void iocLimitExpires() {
        Order order = orderService.place(limit("ioc-1", Side.BUY, 1, "49900", TimeInForce.IOC)).order();

        assertThat(order.state()).isEqualTo(OrderState.EXPIRED);
        assertThat(states(order.id())).containsExactly(OrderState.NEW, OrderState.APPROVED, OrderState.SENT,
                OrderState.ACKED, OrderState.EXPIRED);
        assertThat(fills(order.id())).isEmpty();
    }

    @Test
    void cancelRestingOrder() {
        Order resting = orderService.place(limit("cxl-1", Side.BUY, 1, "49900", TimeInForce.GTC)).order();

        Order cancelled = orderService.cancel(resting.id());

        assertThat(cancelled.state()).isEqualTo(OrderState.CANCELLED);
        assertThat(states(resting.id())).containsExactly(OrderState.NEW, OrderState.APPROVED, OrderState.SENT,
                OrderState.ACKED, OrderState.CANCEL_REQUESTED, OrderState.CANCELLED);
        assertThat(auditCount("order.updated")).isEqualTo(6);
    }

    @Test
    void cancelAfterFillIsConflict() {
        Order filled = orderService.place(market("cxl-2", Side.BUY, 1)).order();

        assertThatThrownBy(() -> orderService.cancel(filled.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(orderService.details(filled.id()).orElseThrow().order().state()).isEqualTo(OrderState.FILLED);
    }

    @Test
    void cancelUnknownOrderIsNotFound() {
        assertThatThrownBy(() -> orderService.cancel(UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void detailsAndListReturnStoredOrders() {
        Order filled = orderService.place(market("list-1", Side.BUY, 1)).order();
        Order resting = orderService.place(limit("list-2", Side.BUY, 1, "49900", TimeInForce.GTC)).order();

        OrderDetails details = orderService.details(filled.id()).orElseThrow();
        assertThat(details.transitions()).hasSize(5);
        assertThat(details.fills()).hasSize(1);
        assertThat(orderService.details(UUID.randomUUID())).isEmpty();
        assertThat(orderService.list(Optional.empty())).extracting(Order::id).contains(filled.id(), resting.id());
        assertThat(orderService.list(Optional.of(OrderState.ACKED))).extracting(Order::id).containsExactly(resting.id());
    }

    @Nested
    @TestPropertySource(properties = "vajra.trading.mode=DELTA_TESTNET")
    class DeltaTestnetMode {

        @Autowired
        OrderService deltaOrderService;

        @Test
        void deltaTestnetModeHasNoTradingVenue() {
            Order order = deltaOrderService.place(market("delta-1", Side.BUY, 1)).order();

            assertThat(order.venue()).isEqualTo(Venue.DELTA_TESTNET);
            assertThat(order.state()).isEqualTo(OrderState.REJECTED);
            assertThat(order.reason()).isEqualTo("NO_TRADING_VENUE");
            assertThat(states(order.id())).containsExactly(
                    OrderState.NEW, OrderState.APPROVED, OrderState.SENT, OrderState.REJECTED);
            assertThat(fills(order.id())).isEmpty();
        }
    }

    @Nested
    class ThrowingVenue {

        @MockitoBean
        TradingVenue venue;

        @Autowired
        OrderService throwingOrderService;

        @Test
        void venueExceptionMarksUnknown() {
            when(venue.venue()).thenReturn(Venue.PAPER);
            when(venue.place(any(), any())).thenThrow(new IllegalStateException("connection reset"));

            Order order = throwingOrderService.place(market("unk-1", Side.BUY, 1)).order();

            assertThat(order.state()).isEqualTo(OrderState.UNKNOWN);
            assertThat(states(order.id())).containsExactly(
                    OrderState.NEW, OrderState.APPROVED, OrderState.SENT, OrderState.UNKNOWN);
            assertThat(fills(order.id())).isEmpty();
            assertThat(positions.find(Venue.PAPER, btcId)).isEmpty();
        }
    }

    private void assertBadRequest(OrderIntent intent) {
        assertThatThrownBy(() -> orderService.place(intent))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private void quote(String bid, String ask, Instant receivedAt) {
        quotes.put(new Quote("BTCUSD", new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(bid), receivedAt,
                receivedAt));
    }

    private List<OrderState> states(UUID orderId) {
        return orderService.details(orderId).orElseThrow().transitions().stream().map(OrderTransition::to).toList();
    }

    private List<Fill> fills(UUID orderId) {
        return orderService.details(orderId).orElseThrow().fills();
    }

    private long orderCount() {
        return jdbc.sql("select count(*) from orders").query(Long.class).single();
    }

    private long auditCount(String eventType) {
        return jdbc.sql("select count(*) from audit_event where event_type = :type")
                .param("type", eventType)
                .query(Long.class)
                .single();
    }

    private static OrderIntent market(String key, Side side, long size) {
        return new OrderIntent(key, "BTCUSD", side, OrderType.MARKET, size, null, null, false, OrderSource.MANUAL);
    }

    private static OrderIntent limit(String key, Side side, long size, String price, TimeInForce tif) {
        return new OrderIntent(key, "BTCUSD", side, OrderType.LIMIT, size, price == null ? null : new BigDecimal(price),
                tif, false, OrderSource.MANUAL);
    }

    private static Instrument instrument(String symbol, boolean enabled, Instant now) {
        return new Instrument(Venue.DELTA_TESTNET, symbol, symbol.hashCode(), "perpetual_futures",
                new BigDecimal("0.001"), new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"),
                new BigDecimal("0.0005"), enabled, now);
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
