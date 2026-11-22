package com.vajra.coreservice.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.connector.VenueResult;
import com.vajra.coreservice.connector.delta.DeltaPrivateClient;
import com.vajra.coreservice.connector.delta.DeltaTradingVenue;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.marketdata.QuoteCache;
import com.vajra.coreservice.position.Position;
import com.vajra.coreservice.position.PositionRepository;
import com.vajra.coreservice.risk.RiskDecision;
import com.vajra.coreservice.risk.RiskGate;
import com.vajra.coreservice.risk.TradingStateRepository;
import com.vajra.coreservice.testsupport.TestDatabase;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import(KillServiceIT.RiskOverride.class)
class KillServiceIT {

    /** risk-service's halt rule, inline: kill-switch reduce-only flattens pass while halted, everything else is rejected. */
    static class HaltAwareRiskGate implements RiskGate {

        private final TradingStateRepository tradingState;

        HaltAwareRiskGate(TradingStateRepository tradingState) {
            this.tradingState = tradingState;
        }

        @Override
        public RiskDecision approve(Order order) {
            boolean flatten = order.source() == OrderSource.KILL_SWITCH && order.reduceOnly();
            if (tradingState.halted() && !flatten) {
                return RiskDecision.reject("HALTED");
            }
            return new RiskDecision(true, null, UUID.randomUUID());
        }
    }

    @TestConfiguration
    static class RiskOverride {
        @Bean
        @Primary
        HaltAwareRiskGate haltAwareRiskGate(TradingStateRepository tradingState) {
            return new HaltAwareRiskGate(tradingState);
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
    KillService killService;

    @Autowired
    OrderService orderService;

    @Autowired
    InstrumentRepository instruments;

    @Autowired
    PositionRepository positions;

    @Autowired
    TradingStateRepository tradingState;

    @Autowired
    QuoteCache quotes;

    @Autowired
    Clock clock;

    @Autowired
    JdbcClient jdbc;

    long btcId;

    @BeforeEach
    void resetData() {
        for (String table : List.of("audit_event", "fill", "order_transition", "orders", "position")) {
            jdbc.sql("delete from " + table).update();
        }
        Instant now = clock.instant();
        tradingState.setHalted(false, null, now);
        instruments.upsertAll(List.of(new Instrument(Venue.DELTA_TESTNET, "BTCUSD", 84, "perpetual_futures",
                new BigDecimal("0.001"), new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"),
                new BigDecimal("0.0005"), true, now)));
        btcId = instruments.idOf(Venue.DELTA_TESTNET, "BTCUSD").orElseThrow();
        freshQuote();
    }

    @Test
    void killCancelsOpenOrdersAndFlattens() {
        Order resting = orderService.place(limit("rest-1", Side.BUY, 1, "49000")).order();
        orderService.place(market("open-1", Side.BUY, 3));
        assertThat(resting.state()).isEqualTo(OrderState.ACKED);
        assertThat(positionSize()).isEqualTo(3);

        KillResult result = killService.kill("test kill");

        assertThat(result).isEqualTo(new KillResult(1, 1, true));
        assertThat(orderService.details(resting.id()).orElseThrow().order().state()).isEqualTo(OrderState.CANCELLED);
        List<Order> flattens = killSwitchOrders();
        assertThat(flattens).hasSize(1);
        Order flatten = flattens.get(0);
        assertThat(flatten.state()).isEqualTo(OrderState.FILLED);
        assertThat(flatten.side()).isEqualTo(Side.SELL);
        assertThat(flatten.size()).isEqualTo(3);
        assertThat(flatten.type()).isEqualTo(OrderType.MARKET);
        assertThat(flatten.reduceOnly()).isTrue();
        assertThat(flatten.idempotencyKey()).startsWith("kill-");
        assertThat(positionSize()).isZero();
        assertThat(tradingState.halted()).isTrue();
    }

    @Test
    void killIsIdempotent() {
        orderService.place(market("open-2", Side.BUY, 3));

        killService.kill("first");
        KillResult second = killService.kill("second");

        assertThat(second).isEqualTo(new KillResult(0, 0, true));
        assertThat(killSwitchOrders()).hasSize(1);
        assertThat(positionSize()).isZero();
        assertThat(tradingState.halted()).isTrue();
    }

    @Test
    void killWithStaleQuoteReportsNotFlattened() {
        orderService.place(market("open-3", Side.BUY, 3));
        staleQuote();

        KillResult result = killService.kill("stale");

        assertThat(result).isEqualTo(new KillResult(0, 1, false));
        Order flatten = killSwitchOrders().get(0);
        assertThat(flatten.state()).isEqualTo(OrderState.REJECTED);
        assertThat(flatten.reason()).isEqualTo("STALE_PRICE");
        assertThat(positionSize()).isEqualTo(3);
        assertThat(tradingState.halted()).isTrue();
    }

    @Test
    void killRetryAfterStaleQuoteFlattens() {
        orderService.place(market("open-4", Side.BUY, 3));
        staleQuote();
        assertThat(killService.kill("stale").flattened()).isFalse();
        freshQuote();

        KillResult retry = killService.kill("retry");

        assertThat(retry).isEqualTo(new KillResult(0, 1, true));
        assertThat(killSwitchOrders()).extracting(Order::state)
                .containsExactlyInAnyOrder(OrderState.REJECTED, OrderState.FILLED);
        assertThat(positionSize()).isZero();
    }

    @Test
    void concurrentKillsFlattenOnce() throws Exception {
        orderService.place(market("open-5", Side.BUY, 3));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<KillResult> task = () -> {
                start.await();
                return killService.kill("race");
            };
            List<Future<KillResult>> futures = List.of(pool.submit(task), pool.submit(task));
            start.countDown();

            List<KillResult> results = futures.stream().map(KillServiceIT::await).toList();

            assertThat(results).allMatch(KillResult::flattened);
            assertThat(results).extracting(KillResult::flattenOrders).containsExactlyInAnyOrder(1, 0);
            assertThat(killSwitchOrders()).hasSize(1);
            assertThat(killSwitchOrders().get(0).state()).isEqualTo(OrderState.FILLED);
            assertThat(positionSize()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ordersRefusedAfterKillUntilResume() {
        killService.kill("halt");

        Order refused = orderService.place(market("after-kill", Side.BUY, 1)).order();
        killService.resume();
        Order accepted = orderService.place(market("after-resume", Side.BUY, 1)).order();

        assertThat(refused.state()).isEqualTo(OrderState.RISK_REJECTED);
        assertThat(refused.reason()).isEqualTo("HALTED");
        assertThat(tradingState.halted()).isFalse();
        assertThat(accepted.state()).isEqualTo(OrderState.FILLED);
    }

    @Test
    void flattenWorksWhenInstrumentTradingDisabled() {
        orderService.place(market("dis-1", Side.BUY, 3));
        instruments.upsertAll(List.of(new Instrument(Venue.DELTA_TESTNET, "BTCUSD", 84, "perpetual_futures",
                new BigDecimal("0.001"), new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"),
                new BigDecimal("0.0005"), false, clock.instant())));

        KillResult result = killService.kill("disabled");

        assertThat(result).isEqualTo(new KillResult(0, 1, true));
        assertThat(killSwitchOrders()).extracting(Order::state).containsExactly(OrderState.FILLED);
        assertThat(positionSize()).isZero();
    }

    @Test
    void killNotFlattenedWhileOrderUnknownOrSent() {
        for (OrderState stuck : List.of(OrderState.UNKNOWN, OrderState.SENT)) {
            Order resting = orderService.place(limit("stuck-" + stuck, Side.BUY, 1, "49000")).order();
            jdbc.sql("update orders set state = :state where id = :id")
                    .param("state", stuck.name())
                    .param("id", resting.id())
                    .update();

            KillResult result = killService.kill("stuck " + stuck);

            assertThat(result.flattened()).as(stuck.name()).isFalse();
            jdbc.sql("update orders set state = 'REJECTED' where id = :id").param("id", resting.id()).update();
        }
        assertThat(killService.kill("clean").flattened()).isTrue();
    }

    @Test
    void positionOnVenueWithoutTradingVenueReportsNotFlattened() {
        positions.save(new Position(Venue.DELTA_TESTNET, btcId, "BTCUSD", 2, new BigDecimal("50000"), BigDecimal.ZERO,
                BigDecimal.ZERO, clock.instant(), 0));

        KillResult result = killService.kill("no venue");

        assertThat(result).isEqualTo(new KillResult(0, 0, false));
        assertThat(killSwitchOrders()).isEmpty();
    }

    @Nested
    @TestPropertySource(properties = {
        "vajra.trading.mode=DELTA_TESTNET", "vajra.delta.api-key=fake-test-key", "vajra.delta.api-secret=fake-test-secret"
    })
    class DeltaTestnetMode {

        @MockitoBean
        DeltaPrivateClient deltaPrivateClient;

        @MockitoBean
        DeltaTradingVenue deltaVenue;

        @Test
        void flattenUsesPositionVenueNotTradingMode() {
            when(deltaVenue.venue()).thenReturn(Venue.DELTA_TESTNET);
            positions.save(new Position(Venue.PAPER, btcId, "BTCUSD", 3, new BigDecimal("50000"), BigDecimal.ZERO,
                    BigDecimal.ZERO, clock.instant(), 0));

            KillResult result = killService.kill("paper position in delta mode");

            assertThat(result).isEqualTo(new KillResult(0, 1, true));
            Order flatten = killSwitchOrders().get(0);
            assertThat(flatten.venue()).isEqualTo(Venue.PAPER);
            assertThat(flatten.state()).isEqualTo(OrderState.FILLED);
            assertThat(positionSize()).isZero();
            verify(deltaVenue, never()).place(any(), any());
        }

        @Test
        void killRecancelsCancelRequestedOrders() {
            when(deltaVenue.venue()).thenReturn(Venue.DELTA_TESTNET);
            when(deltaVenue.place(any(), any())).thenAnswer(inv -> new VenueResult.Resting("v-rest"));
            when(deltaVenue.cancel(any())).thenThrow(new IllegalStateException("Delta 503"));
            Order resting = orderService.place(limit("rc-rest", Side.BUY, 1, "49000")).order();
            assertThat(orderService.cancel(resting.id()).state()).isEqualTo(OrderState.CANCEL_REQUESTED);
            org.mockito.Mockito.doAnswer(inv -> new VenueResult.Cancelled("v-rest")).when(deltaVenue).cancel(any());

            KillResult result = killService.kill("second kill");

            assertThat(result).isEqualTo(new KillResult(1, 0, true));
            assertThat(orderService.details(resting.id()).orElseThrow().order().state()).isEqualTo(OrderState.CANCELLED);
        }

        @Test
        void killDuringRateLimitReportsNotFlattened() {
            when(deltaVenue.venue()).thenReturn(Venue.DELTA_TESTNET);
            when(deltaVenue.place(any(), any())).thenAnswer(inv -> {
                Order order = inv.getArgument(0);
                if (order.source() == OrderSource.KILL_SWITCH) {
                    return new VenueResult.Rejected("RATE_LIMITED");
                }
                return order.type() == OrderType.MARKET
                        ? new VenueResult.Filled("v-" + order.idempotencyKey(), "fill-" + order.idempotencyKey(),
                                new BigDecimal("50010"), new BigDecimal("0.02"), "TAKER")
                        : new VenueResult.Resting("v-" + order.idempotencyKey());
            });
            when(deltaVenue.cancel(any())).thenAnswer(inv -> new VenueResult.Cancelled("v"));
            Order resting = orderService.place(limit("rl-rest", Side.BUY, 1, "49000")).order();
            orderService.place(market("rl-open", Side.BUY, 3));
            assertThat(resting.state()).isEqualTo(OrderState.ACKED);

            KillResult result = killService.kill("rate limited");

            assertThat(result).isEqualTo(new KillResult(1, 1, false));
            verify(deltaVenue).cancel(any());
            Order flatten = killSwitchOrders().get(0);
            assertThat(flatten.state()).isEqualTo(OrderState.REJECTED);
            assertThat(flatten.reason()).isEqualTo("RATE_LIMITED");
            assertThat(positions.find(Venue.DELTA_TESTNET, btcId).orElseThrow().size()).isEqualTo(3);
        }
    }

    private List<Order> killSwitchOrders() {
        return orderService.list(Optional.empty()).stream()
                .filter(o -> o.source() == OrderSource.KILL_SWITCH)
                .toList();
    }

    private long positionSize() {
        return positions.find(Venue.PAPER, btcId).map(Position::size).orElse(0L);
    }

    private void freshQuote() {
        quote(clock.instant());
    }

    private void staleQuote() {
        quote(clock.instant().minus(Duration.ofSeconds(60)));
    }

    private void quote(Instant receivedAt) {
        quotes.put(new Quote("BTCUSD", new BigDecimal("50000"), new BigDecimal("50010"), new BigDecimal("50005"),
                receivedAt, receivedAt));
    }

    private static OrderIntent market(String key, Side side, long size) {
        return new OrderIntent(key, "BTCUSD", side, OrderType.MARKET, size, null, null, false, OrderSource.MANUAL, null);
    }

    private static OrderIntent limit(String key, Side side, long size, String price) {
        return new OrderIntent(key, "BTCUSD", side, OrderType.LIMIT, size, new BigDecimal(price), TimeInForce.GTC,
                false, OrderSource.MANUAL, null);
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
