package com.vajra.coreservice.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.connector.VenueResult;
import com.vajra.coreservice.connector.delta.DeltaFill;
import com.vajra.coreservice.connector.delta.DeltaOrder;
import com.vajra.coreservice.connector.delta.DeltaPosition;
import com.vajra.coreservice.connector.delta.DeltaPrivateClient;
import com.vajra.coreservice.connector.delta.DeltaRateLimitGuard;
import com.vajra.coreservice.connector.delta.DeltaTradingVenue;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.position.Position;
import com.vajra.coreservice.position.PositionRepository;
import com.vajra.coreservice.risk.TradingStateRepository;
import com.vajra.coreservice.testsupport.ApprovingRiskGate;
import com.vajra.coreservice.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real Postgres and the real {@link DeltaTradingVenue}; only {@link DeltaPrivateClient} (the network) is a stub. */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = "vajra.scheduling.enabled=false")
@Import(ReconcilerIT.Config.class)
class ReconcilerIT {

    private static final long DELTA_ORDER_ID = 555;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ApprovingRiskGate approvingRiskGate() {
            return new ApprovingRiskGate();
        }

        @Bean
        DeltaTradingVenue deltaTradingVenue(
                DeltaPrivateClient client, InstrumentRepository instruments, DeltaRateLimitGuard guard, Clock clock) {
            return new DeltaTradingVenue(client, instruments, guard, clock);
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

    @MockitoBean
    DeltaPrivateClient client;

    @Autowired
    Reconciler reconciler;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderRepository orders;

    @Autowired
    FillRepository fills;

    @Autowired
    InstrumentRepository instruments;

    @Autowired
    PositionRepository positions;

    @Autowired
    TradingStateRepository tradingState;

    @Autowired
    Clock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DeltaTradingVenue deltaVenue;

    long btcId;

    @BeforeEach
    void resetData() {
        for (String table : List.of("audit_event", "fill", "order_transition", "orders", "position")) {
            jdbc.sql("delete from " + table).update();
        }
        Instant now = clock.instant();
        tradingState.setHalted(false, null, now);
        instruments.upsertAll(List.of(new Instrument(Venue.DELTA_TESTNET, "BTCUSD", 27, "perpetual_futures",
                new BigDecimal("0.001"), new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"),
                new BigDecimal("0.0005"), true, now)));
        btcId = instruments.idOf(Venue.DELTA_TESTNET, "BTCUSD").orElseThrow();
        when(client.fills(any())).thenReturn(List.of());
        when(client.position(anyLong())).thenReturn(new DeltaPosition(0, BigDecimal.ZERO));
        when(client.orderByClientId(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void unknownOrderFilledAtVenueIsResolvedOnce() {
        Order order = insert("u-1", Venue.DELTA_TESTNET, OrderState.UNKNOWN, null, ago(Duration.ofSeconds(5)));
        deltaFilled(order, "fill-1");
        when(client.position(27)).thenReturn(new DeltaPosition(1, new BigDecimal("50000")));

        Reconciler.ReconcileSummary first = reconciler.run();

        Order after = orders.find(order.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(OrderState.FILLED);
        assertThat(after.venueOrderId()).isEqualTo("555");
        assertThat(fills.findByOrder(order.id())).hasSize(1);
        assertThat(positions.find(Venue.DELTA_TESTNET, btcId).orElseThrow().size()).isEqualTo(1);
        assertThat(first.resolved()).isEqualTo(1);

        reconciler.run();

        assertThat(fills.findByOrder(order.id())).hasSize(1);
        assertThat(positions.find(Venue.DELTA_TESTNET, btcId).orElseThrow().size()).isEqualTo(1);
        assertThat(orders.find(order.id()).orElseThrow().version()).isEqualTo(after.version());
    }

    @Test
    void unknownOrderNotFoundAfter60sIsRejected() {
        Order order = insert("u-2", Venue.DELTA_TESTNET, OrderState.UNKNOWN, null, ago(Duration.ofSeconds(90)));

        reconciler.run();

        Order after = orders.find(order.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(OrderState.REJECTED);
        assertThat(after.reason()).isEqualTo("NOT_FOUND_AT_VENUE");
    }

    @Test
    void unknownOrderNotFoundYoungIsLeft() {
        Order order = insert("u-3", Venue.DELTA_TESTNET, OrderState.UNKNOWN, null, ago(Duration.ofSeconds(10)));

        reconciler.run();

        assertThat(orders.find(order.id()).orElseThrow().state()).isEqualTo(OrderState.UNKNOWN);
    }

    @Test
    void stuckNewAndApprovedAreClosedNotSent() {
        Order stuckNew = insert("s-new", Venue.DELTA_TESTNET, OrderState.NEW, null, ago(Duration.ofSeconds(90)));
        Order stuckApproved = insert("s-app", Venue.DELTA_TESTNET, OrderState.APPROVED, null, ago(Duration.ofSeconds(90)));
        Order youngNew = insert("s-young", Venue.DELTA_TESTNET, OrderState.NEW, null, ago(Duration.ofSeconds(5)));

        reconciler.run();

        Order closedNew = orders.find(stuckNew.id()).orElseThrow();
        assertThat(closedNew.state()).isEqualTo(OrderState.RISK_REJECTED);
        assertThat(closedNew.reason()).isEqualTo("STUCK_NOT_SENT");
        assertThat(orders.riskDecisionId(stuckNew.id())).isEmpty();
        Order closedApproved = orders.find(stuckApproved.id()).orElseThrow();
        assertThat(closedApproved.state()).isEqualTo(OrderState.REJECTED);
        assertThat(closedApproved.reason()).isEqualTo("STUCK_NOT_SENT");
        assertThat(orders.transitions(stuckApproved.id())).extracting(OrderTransition::to)
                .containsExactly(OrderState.SENT, OrderState.REJECTED);
        assertThat(orders.find(youngNew.id()).orElseThrow().state()).isEqualTo(OrderState.NEW);
        verify(client, never()).placeOrder(any());
    }

    @Test
    void stuckSentBecomesUnknownThenResolved() {
        Order order = insert("s-sent", Venue.DELTA_TESTNET, OrderState.SENT, null, ago(Duration.ofSeconds(40)));
        deltaFilled(order, "fill-s");

        reconciler.run();

        assertThat(orders.find(order.id()).orElseThrow().state()).isEqualTo(OrderState.FILLED);
        assertThat(orders.transitions(order.id())).extracting(OrderTransition::to)
                .containsSubsequence(OrderState.UNKNOWN, OrderState.ACKED, OrderState.FILLED);
        assertThat(fills.findByOrder(order.id())).hasSize(1);
    }

    @Test
    void missingFillForKnownOrderIsRecorded() {
        Order order = insert("m-1", Venue.DELTA_TESTNET, OrderState.ACKED, "555", ago(Duration.ofSeconds(5)));
        deltaFilled(order, "fill-m");

        reconciler.run();

        assertThat(orders.find(order.id()).orElseThrow().state()).isEqualTo(OrderState.FILLED);
        assertThat(fills.findByOrder(order.id())).extracting(Fill::venueFillId).containsExactly("fill-m");
    }

    @Test
    void fillForUnknownDeltaOrderIsLoggedNotRecorded(CapturedOutput output) {
        when(client.fills(any())).thenReturn(List.of(new DeltaFill(
                "fill-x", 999, 1, new BigDecimal("50000"), new BigDecimal("0.01"), "taker", clock.instant())));

        reconciler.run();

        assertThat(output).contains("fill-x").contains("unknown Delta order 999").contains("not recorded");
        assertThat(jdbc.sql("select count(*) from fill").query(Long.class).single()).isZero();
    }

    @Test
    void fillIdInsideMergedIdIsAlreadyRecordedAndFilledOrderMismatchIsLoggedNotInserted(CapturedOutput output) {
        Order order = insert("m-2", Venue.DELTA_TESTNET, OrderState.FILLED, "555", ago(Duration.ofSeconds(5)));
        fills.insertIfNew(new Fill(UUID.randomUUID(), order.id(), Venue.DELTA_TESTNET, "fill-a,fill-b",
                new BigDecimal("50000"), 1, BigDecimal.ZERO, "TAKER", clock.instant()));
        when(client.fills(any())).thenReturn(List.of(
                new DeltaFill("fill-b", 555, 1, new BigDecimal("50000"), BigDecimal.ZERO, "taker", clock.instant()),
                new DeltaFill("fill-c", 555, 1, new BigDecimal("50000"), BigDecimal.ZERO, "taker", clock.instant())));

        Reconciler.ReconcileSummary summary = reconciler.run();

        assertThat(fills.findByOrder(order.id())).hasSize(1);
        assertThat(output).contains("fill-c").doesNotContain("Delta fill fill-b");
        assertThat(summary.mismatches()).isEqualTo(1);
    }

    @Test
    void positionMismatchLoggedNeverOverwritten(CapturedOutput output) {
        positions.save(new Position(Venue.DELTA_TESTNET, btcId, "BTCUSD", 2, new BigDecimal("50000"), BigDecimal.ZERO,
                BigDecimal.ZERO, clock.instant(), 0));
        when(client.position(27)).thenReturn(new DeltaPosition(3, new BigDecimal("50100")));

        reconciler.run();

        assertThat(output).contains("ours size=2").contains("Delta size=3");
        Position ours = positions.find(Venue.DELTA_TESTNET, btcId).orElseThrow();
        assertThat(ours.size()).isEqualTo(2);
        assertThat(ours.avgEntryPrice()).isEqualByComparingTo("50000");
    }

    @Test
    void entryPriceMismatchBeyondOneTickLoggedNeverOverwritten(CapturedOutput output) {
        positions.save(new Position(Venue.DELTA_TESTNET, btcId, "BTCUSD", 2, new BigDecimal("50000"), BigDecimal.ZERO,
                BigDecimal.ZERO, clock.instant(), 0));
        when(client.position(27)).thenReturn(new DeltaPosition(2, new BigDecimal("50001")));

        reconciler.run();

        assertThat(output).contains("ours=50000").contains("Delta=50001");
        assertThat(positions.find(Venue.DELTA_TESTNET, btcId).orElseThrow().avgEntryPrice()).isEqualByComparingTo("50000");
    }

    /**
     * Races the two real FillRecorder entry points on one order and one Delta fill: the checker path
     * ({@code onRestingCheck}) and the path reconciler step 3 uses for an open order ({@code resolve} then
     * {@code applyResolved}). Exactly one fill row and one position change per round.
     */
    @Test
    void sameFillFromCheckerAndReconcilerRecordedOnce() throws Exception {
        Instrument instrument = instruments.find(Venue.DELTA_TESTNET, "BTCUSD").orElseThrow();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 1; i <= 25; i++) {
                Order order = insert("race-" + i, Venue.DELTA_TESTNET, OrderState.ACKED, "555", ago(Duration.ofSeconds(5)));
                deltaFilled(order, "fill-race-" + i);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> checkerPath = pool.submit(() -> {
                    await(start);
                    orderService.onRestingCheck(order);
                });
                Future<?> reconcilerPath = pool.submit(() -> {
                    await(start);
                    VenueResult answer = deltaVenue.resolve(order, instrument).orElseThrow();
                    orderService.applyResolved(order, answer, instrument);
                });
                start.countDown();
                for (Future<?> f : List.of(checkerPath, reconcilerPath)) {
                    try {
                        f.get();
                    } catch (ExecutionException e) {
                        assertThat(e.getCause()).as("round %d", i).isInstanceOf(OptimisticLockingFailureException.class);
                    }
                }

                assertThat(fills.findByOrder(order.id())).as("fills, round %d", i).hasSize(1);
                assertThat(positions.find(Venue.DELTA_TESTNET, btcId).orElseThrow().size()).as("position, round %d", i).isEqualTo(i);
                assertThat(orders.find(order.id()).orElseThrow().state()).isEqualTo(OrderState.FILLED);
                for (String table : List.of("audit_event", "fill", "order_transition", "orders")) {
                    jdbc.sql("delete from " + table).update();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failingStepDoesNotStopLaterSteps(CapturedOutput output) {
        when(client.fills(any())).thenThrow(new IllegalStateException("boom"));
        when(client.position(27)).thenReturn(new DeltaPosition(4, new BigDecimal("50000")));

        reconciler.run();

        assertThat(output).contains("Reconcile step 'missing fills' failed").contains("Delta size=4");
    }

    @Test
    void paperModeSkipsVenueSteps(CapturedOutput output) {
        Reconciler paperOnly = new Reconciler(orders, fills, positions, instruments, orderService, Optional.empty(), client, clock);
        Order stuckSent = insert("p-sent", Venue.PAPER, OrderState.SENT, null, ago(Duration.ofSeconds(40)));
        Order unknown = insert("p-unknown", Venue.PAPER, OrderState.UNKNOWN, null, ago(Duration.ofSeconds(90)));

        paperOnly.run();

        verifyNoInteractions(client);
        assertThat(orders.find(stuckSent.id()).orElseThrow().state()).isEqualTo(OrderState.UNKNOWN);
        assertThat(orders.find(unknown.id()).orElseThrow().state()).isEqualTo(OrderState.UNKNOWN);
        assertThat(output).contains("cannot be resolved").contains(unknown.id().toString());
    }

    private void deltaFilled(Order order, String fillId) {
        when(client.orderByClientId(order.clientOrderId())).thenReturn(Optional.of(
                new DeltaOrder(DELTA_ORDER_ID, order.clientOrderId(), "closed", 1, 0, new BigDecimal("50000"),
                        new BigDecimal("0.01"), 27)));
        when(client.fills(any())).thenReturn(List.of(new DeltaFill(
                fillId, DELTA_ORDER_ID, 1, new BigDecimal("50000"), new BigDecimal("0.01"), "taker", clock.instant())));
    }

    private Instant ago(Duration duration) {
        return clock.instant().minus(duration);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Order insert(String key, Venue venue, OrderState state, String venueOrderId, Instant updatedAt) {
        Order order = new Order(UUID.randomUUID(), ClientOrderIds.fromKey(key), key, venue, btcId, "BTCUSD", Side.BUY,
                OrderType.LIMIT, 1, new BigDecimal("50000"), TimeInForce.GTC, false, state, 0, null, BigDecimal.ZERO,
                venueOrderId, OrderSource.MANUAL, null, updatedAt, updatedAt, 0);
        orders.insert(order, "hash-" + key, null);
        return order;
    }
}
