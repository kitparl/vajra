package com.vajra.coreservice.oms;

import static org.assertj.core.api.Assertions.assertThat;

import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.connector.TradingVenue;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.connector.VenueResult;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.marketdata.QuoteCache;
import com.vajra.coreservice.position.PositionRepository;
import com.vajra.coreservice.risk.TradingStateRepository;
import com.vajra.coreservice.testsupport.ApprovingRiskGate;
import com.vajra.coreservice.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = "vajra.scheduling.enabled=false")
@Import(OpenOrderCheckerIT.Config.class)
class OpenOrderCheckerIT {

    /** Stands in for Delta: answers whatever the test sets. */
    static class StubDeltaVenue implements TradingVenue {
        volatile Optional<VenueResult> answer = Optional.empty();
        volatile java.util.function.Supplier<VenueResult> cancelAnswer = () -> {
            throw new UnsupportedOperationException();
        };

        @Override
        public Venue venue() {
            return Venue.DELTA_TESTNET;
        }

        @Override
        public VenueResult place(Order order, Instrument instrument) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VenueResult cancel(Order order) {
            return cancelAnswer.get();
        }

        @Override
        public Optional<VenueResult> checkResting(Order order, Instrument instrument) {
            return answer;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ApprovingRiskGate approvingRiskGate() {
            return new ApprovingRiskGate();
        }

        @Bean
        StubDeltaVenue stubDeltaVenue() {
            return new StubDeltaVenue();
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
    OpenOrderChecker checker;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderRepository orders;

    @Autowired
    InstrumentRepository instruments;

    @Autowired
    PositionRepository positions;

    @Autowired
    QuoteCache quotes;

    @Autowired
    TradingStateRepository tradingState;

    @Autowired
    ApprovingRiskGate riskGate;

    @Autowired
    StubDeltaVenue delta;

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
        riskGate.reset();
        delta.answer = Optional.empty();
        delta.cancelAnswer = () -> {
            throw new UnsupportedOperationException();
        };
        instruments.upsertAll(List.of(new Instrument(Venue.DELTA_TESTNET, "BTCUSD", 27, "perpetual_futures",
                new BigDecimal("0.001"), new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"),
                new BigDecimal("0.0005"), true, now)));
        btcId = instruments.idOf(Venue.DELTA_TESTNET, "BTCUSD").orElseThrow();
    }

    @Test
    void cancelRequestedOrderResolvedByVenueCancelled() {
        Order order = insert("cr-1", Venue.DELTA_TESTNET, OrderState.CANCEL_REQUESTED);
        delta.answer = Optional.of(new VenueResult.Cancelled("123"));

        checker.run();

        assertThat(orders.find(order.id()).orElseThrow().state()).isEqualTo(OrderState.CANCELLED);
    }

    @Test
    void cancelRequestedStillLiveAtVenueIsRecancelledByChecker() {
        Order order = insert("rec-1", Venue.DELTA_TESTNET, OrderState.ACKED);
        delta.cancelAnswer = () -> {
            throw new IllegalStateException("Delta 503");
        };
        assertThat(orderService.cancel(order.id()).state()).isEqualTo(OrderState.CANCEL_REQUESTED);
        delta.cancelAnswer = () -> new VenueResult.Cancelled("123");

        checker.run();

        assertThat(orders.find(order.id()).orElseThrow().state()).isEqualTo(OrderState.CANCELLED);
    }

    @Test
    void restingDeltaOrderCancelledAtVenueGoesThroughCancelRequested() {
        Order order = insert("rc-1", Venue.DELTA_TESTNET, OrderState.ACKED);
        delta.answer = Optional.of(new VenueResult.Cancelled("123"));

        checker.run();

        assertThat(orders.find(order.id()).orElseThrow().state()).isEqualTo(OrderState.CANCELLED);
        assertThat(orders.transitions(order.id())).extracting(OrderTransition::to)
                .containsExactly(OrderState.CANCEL_REQUESTED, OrderState.CANCELLED);
    }

    @Test
    void restingDeltaOrderExpiredAtVenue() {
        Order order = insert("ex-1", Venue.DELTA_TESTNET, OrderState.ACKED);
        delta.answer = Optional.of(new VenueResult.Expired("123"));

        checker.run();

        assertThat(orders.find(order.id()).orElseThrow().state()).isEqualTo(OrderState.EXPIRED);
    }

    @Test
    void restingDeltaOrderFilledByChecker() {
        Order order = insert("rf-1", Venue.DELTA_TESTNET, OrderState.ACKED);
        delta.answer = Optional.of(new VenueResult.Filled(
                "123", "delta-fill-1", new BigDecimal("50000"), new BigDecimal("0.01"), "MAKER"));

        checker.run();

        Order after = orders.find(order.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(OrderState.FILLED);
        assertThat(orderService.details(order.id()).orElseThrow().fills()).hasSize(1);
        assertThat(positions.find(Venue.DELTA_TESTNET, btcId).orElseThrow().size()).isEqualTo(1);
    }

    @Test
    void openAtVenueChangesNothing() {
        Order order = insert("op-1", Venue.DELTA_TESTNET, OrderState.ACKED);

        checker.run();

        assertThat(orders.find(order.id()).orElseThrow().state()).isEqualTo(OrderState.ACKED);
    }

    @Test
    void paperBehaviourUnchanged() {
        Instant now = clock.instant();
        quote("50000", "50010", now);
        Order resting = orderService.place(new OrderIntent("paper-rest", "BTCUSD", Side.BUY, OrderType.LIMIT, 1,
                new BigDecimal("49900"), TimeInForce.GTC, false, OrderSource.MANUAL, null)).order();
        assertThat(resting.state()).isEqualTo(OrderState.ACKED);

        quote("49880", "49890", now);

        checker.run();

        Order filled = orders.find(resting.id()).orElseThrow();
        assertThat(filled.state()).isEqualTo(OrderState.FILLED);
        assertThat(filled.venue()).isEqualTo(Venue.PAPER);
        assertThat(positions.find(Venue.PAPER, btcId).orElseThrow().size()).isEqualTo(1);
    }

    private void quote(String bid, String ask, Instant at) {
        quotes.put(new Quote("BTCUSD", new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(bid), at, at));
    }

    private Order insert(String key, Venue venue, OrderState state) {
        Instant now = clock.instant();
        Order order = new Order(UUID.randomUUID(), ClientOrderIds.fromKey(key), key, venue, btcId, "BTCUSD", Side.BUY,
                OrderType.LIMIT, 1, new BigDecimal("50000"), TimeInForce.GTC, false, state, 0, null, BigDecimal.ZERO,
                "123", OrderSource.MANUAL, null, now, now, 0);
        orders.insert(order, "hash-" + key, null);
        return order;
    }
}
