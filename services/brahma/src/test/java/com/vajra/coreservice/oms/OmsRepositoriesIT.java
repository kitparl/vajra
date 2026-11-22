package com.vajra.coreservice.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vajra.coreservice.audit.AuditWriter;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.position.Position;
import com.vajra.coreservice.position.PositionRepository;
import com.vajra.coreservice.testsupport.MutableClock;
import com.vajra.coreservice.testsupport.TestDatabase;
import com.vajra.contracts.common.v1.EventEnvelope;
import com.vajra.contracts.events.v1.OrderUpdated;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class OmsRepositoriesIT {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    static DataSource dataSource;
    static JdbcClient jdbc;
    static OrderRepository orders;
    static FillRepository fills;
    static PositionRepository positions;
    static AuditWriter audit;
    static long btcId;

    @BeforeAll
    static void setUp() {
        dataSource = TestDatabase.migratedDataSource(POSTGRES);
        jdbc = JdbcClient.create(dataSource);
        InstrumentRepository instruments = new InstrumentRepository(jdbc);
        instruments.upsertAll(List.of(new Instrument(Venue.DELTA_TESTNET, "BTCUSD", 84, "perpetual_futures",
                new BigDecimal("0.001"), new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"),
                new BigDecimal("0.0005"), true, NOW)));
        btcId = instruments.idOf(Venue.DELTA_TESTNET, "BTCUSD").orElseThrow();
        orders = new OrderRepository(jdbc);
        fills = new FillRepository(jdbc);
        positions = new PositionRepository(jdbc);
        audit = new AuditWriter(jdbc, new MutableClock(NOW));
    }

    @Test
    void duplicateClientOrderIdIsRejected() {
        orders.insert(order("dup-1"), "hash", null);

        assertThatThrownBy(() -> orders.insert(order("dup-1"), "hash", null)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void insertedOrderRoundTripsWithSymbolAndHash() {
        Order o = order("round-1");
        orders.insert(o, "h-round", null);

        Order found = orders.find(o.id()).orElseThrow();
        assertThat(found).usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(o);
        assertThat(found.symbol()).isEqualTo("BTCUSD");
        assertThat(orders.findByClientOrderId("round-1")).map(Order::id).contains(o.id());
        assertThat(orders.requestHash(o.id())).contains("h-round");
        assertThat(orders.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void staleVersionUpdateIsRejected() {
        Order o = order("stale-1");
        orders.insert(o, "hash", null);
        Order loaded = orders.find(o.id()).orElseThrow();

        Order updated = orders.update(withState(loaded, OrderState.APPROVED));
        assertThat(updated.version()).isEqualTo(1);
        assertThat(orders.find(o.id()).orElseThrow().state()).isEqualTo(OrderState.APPROVED);

        assertThatThrownBy(() -> orders.update(withState(loaded, OrderState.RISK_REJECTED)))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void transitionsAndQueriesWork() {
        Order open = order("q-open");
        open = withState(open, OrderState.ACKED);
        orders.insert(open, "h", null);
        orders.addTransition(open.id(), new OrderTransition(null, OrderState.NEW, "created", NOW));
        orders.addTransition(open.id(), new OrderTransition(OrderState.NEW, OrderState.APPROVED, null, NOW.plusSeconds(1)));

        assertThat(orders.transitions(open.id())).extracting(OrderTransition::to)
                .containsExactly(OrderState.NEW, OrderState.APPROVED);
        assertThat(orders.transitions(open.id()).getFirst().from()).isNull();
        assertThat(orders.findOpen(Venue.PAPER)).extracting(Order::id).contains(open.id());
        assertThat(orders.findOpen(Venue.DELTA_TESTNET)).extracting(Order::id).doesNotContain(open.id());
        assertThat(orders.findByState(Optional.of(OrderState.ACKED), 100)).extracting(Order::id).contains(open.id());
        assertThat(orders.findByState(Optional.empty(), 1)).hasSize(1);
    }

    @Test
    void replayedFillIsIgnored() {
        Order o = order("fill-1");
        orders.insert(o, "hash", null);
        Fill fill = new Fill(UUID.randomUUID(), o.id(), Venue.PAPER, "vf-1", new BigDecimal("100.5"), 2,
                new BigDecimal("0.05"), "taker", NOW);
        Fill replay = new Fill(UUID.randomUUID(), o.id(), Venue.PAPER, "vf-1", new BigDecimal("100.5"), 2,
                new BigDecimal("0.05"), "taker", NOW);

        assertThat(fills.insertIfNew(fill)).isTrue();
        assertThat(fills.insertIfNew(replay)).isFalse();
        assertThat(fills.findByOrder(o.id())).hasSize(1);
        assertThat(fills.findByOrder(o.id()).getFirst().id()).isEqualTo(fill.id());
    }

    @Test
    void positionSaveChecksVersion() {
        Position fresh = new Position(Venue.PAPER, btcId, "BTCUSD", 3, new BigDecimal("100.123456789012345678901234567890"),
                BigDecimal.ONE, new BigDecimal("0.1"), NOW, 0);
        assertThat(positions.save(fresh).version()).isEqualTo(1);

        Position loaded = positions.find(Venue.PAPER, btcId).orElseThrow();
        assertThat(loaded.version()).isEqualTo(1);
        assertThat(loaded.symbol()).isEqualTo("BTCUSD");
        assertThat(loaded.avgEntryPrice()).isEqualByComparingTo("100.123456789012345678901234567890");

        assertThat(positions.save(loaded).version()).isEqualTo(2);
        assertThat(positions.find(Venue.PAPER, btcId).orElseThrow().version()).isEqualTo(2);
        assertThat(positions.findAll()).extracting(Position::symbol).contains("BTCUSD");

        assertThatThrownBy(() -> positions.save(loaded)).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void secondFirstInsertOfSamePositionIsOptimisticLockFailure() {
        Position first = new Position(Venue.DELTA_TESTNET, btcId, "BTCUSD", 1, BigDecimal.TEN, BigDecimal.ZERO,
                BigDecimal.ZERO, NOW, 0);
        positions.save(first);

        assertThatThrownBy(() -> positions.save(first)).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void auditEventStoresParsableEnvelope() throws Exception {
        String orderId = UUID.randomUUID().toString();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        tx.executeWithoutResult(s -> audit.record("order.updated", "BTCUSD", OrderUpdated.newBuilder().setOrderId(orderId).build()));

        byte[] payload = jdbc.sql("select payload from audit_event where event_type = 'order.updated'")
                .query(byte[].class).single();
        EventEnvelope envelope = EventEnvelope.parseFrom(payload);
        assertThat(envelope.getPayload().unpack(OrderUpdated.class).getOrderId()).isEqualTo(orderId);
        assertThat(envelope.getProducer()).isEqualTo("core-service");
        assertThat(envelope.getEventType()).isEqualTo("order.updated");
        assertThat(jdbc.sql("select count(*) from audit_event where event_type = 'order.updated' and published_at is null")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void auditWriterRequiresTransaction() {
        assertThatThrownBy(() -> audit.record("order.updated", "BTCUSD", OrderUpdated.getDefaultInstance()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Order order(String clientOrderId) {
        return new Order(UUID.randomUUID(), clientOrderId, "key-" + clientOrderId, Venue.PAPER, btcId, "BTCUSD",
                Side.BUY, OrderType.LIMIT, 5, new BigDecimal("100.5"), TimeInForce.GTC, false, OrderState.NEW, 0,
                null, BigDecimal.ZERO, null, OrderSource.MANUAL, null, NOW, NOW, 0);
    }

    private static Order withState(Order o, OrderState state) {
        return new Order(o.id(), o.clientOrderId(), o.idempotencyKey(), o.venue(), o.instrumentId(), o.symbol(),
                o.side(), o.type(), o.size(), o.limitPrice(), o.timeInForce(), o.reduceOnly(), state, o.filledSize(),
                o.avgFillPrice(), o.fees(), o.venueOrderId(), o.source(), o.reason(), o.createdAt(), o.updatedAt(),
                o.version());
    }
}
