package com.vajra.coreservice.signal;

import static org.assertj.core.api.Assertions.assertThat;

import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.marketdata.QuoteCache;
import com.vajra.coreservice.oms.Order;
import com.vajra.coreservice.oms.OrderService;
import com.vajra.coreservice.oms.OrderSource;
import com.vajra.coreservice.oms.OrderState;
import com.vajra.coreservice.risk.TradingStateRepository;
import com.vajra.coreservice.testsupport.ApprovingRiskGate;
import com.vajra.coreservice.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = "vajra.scheduling.enabled=false")
@Import(SignalListenerIT.RiskOverride.class)
class SignalListenerIT {

    @TestConfiguration
    static class RiskOverride {
        @Bean
        @Primary
        ApprovingRiskGate approvingRiskGate() {
            return new ApprovingRiskGate();
        }
    }

    private static final String TOPIC = "signal.generated";
    private static final String GROUP = "core-service";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(TestDatabase.kafkaImage())
            .asCompatibleSubstituteFor("apache/kafka"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
        registry.add("KAFKA_BOOTSTRAP", KAFKA::getBootstrapServers);
        registry.add("RISK_API_TOKEN", () -> "test-risk-token");
        registry.add("CORE_INTERNAL_TOKEN", () -> "test-internal-token");
        // Registered after registerAsServiceRole, which turns the listener off for the other ITs.
        registry.add("vajra.signals.enabled", () -> "true");
    }

    /** The topic is shared by every test in this class, so committed offsets are cumulative. */
    private static long produced;
    private static KafkaProducer<String, byte[]> producer;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrderService orders;

    @Autowired
    InstrumentRepository instruments;

    @Autowired
    QuoteCache quotes;

    @Autowired
    Clock clock;

    @Autowired
    TradingStateRepository tradingState;

    @BeforeAll
    static void createTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        }
        producer = new KafkaProducer<>(Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
    }

    @BeforeEach
    void reset() {
        for (String table : List.of("audit_event", "fill", "order_transition", "orders", "position")) {
            jdbc.sql("delete from " + table).update();
        }
        Instant now = clock.instant();
        tradingState.setHalted(false, null, now);
        instruments.upsertAll(List.of(new Instrument(Venue.DELTA_TESTNET, "BTCUSD", "BTCUSD".hashCode(),
                "perpetual_futures", new BigDecimal("0.001"), new BigDecimal("0.1"), BigDecimal.ONE,
                new BigDecimal("0.0002"), new BigDecimal("0.0005"), true, now)));
        quotes.put(new Quote("BTCUSD", new BigDecimal("50000"), new BigDecimal("50010"), new BigDecimal("50000"), now,
                now));
    }

    @Test
    void heartbeatSignalBecomesFilledPaperOrder() {
        UUID signalId = UUID.randomUUID();

        send(SignalMapperTest.envelope(SignalMapperTest.signal(signalId.toString(), "BTCUSD", "BUY", "MARKET", 1)));

        awaitCommitted();
        Order order = onlyOrder();
        assertThat(order.source()).isEqualTo(OrderSource.SIGNAL);
        assertThat(order.state()).isEqualTo(OrderState.FILLED);
        var details = orders.details(order.id()).orElseThrow();
        assertThat(details.signalId()).isEqualTo(signalId);
        assertThat(details.riskDecisionId()).isNotNull();
    }

    @Test
    void duplicateSignalPlacesOneOrder() {
        String signalId = UUID.randomUUID().toString();

        // Each envelope gets a new event_id, like signal-service's resends.
        send(SignalMapperTest.envelope(SignalMapperTest.signal(signalId, "BTCUSD", "BUY", "MARKET", 1)));
        send(SignalMapperTest.envelope(SignalMapperTest.signal(signalId, "BTCUSD", "BUY", "MARKET", 1)));

        awaitCommitted();
        assertThat(onlyOrder().state()).isEqualTo(OrderState.FILLED);
    }

    @Test
    void poisonMessageIsSkippedAndNextSignalProcessed() {
        send("garbage".getBytes(StandardCharsets.UTF_8));
        send(SignalMapperTest.envelope(
                SignalMapperTest.signal(UUID.randomUUID().toString(), "BTCUSD", "SELL", "MARKET", 1)));

        awaitCommitted();
        assertThat(onlyOrder().state()).isEqualTo(OrderState.FILLED);
    }

    @Test
    void signalWhileHaltedIsRiskRejectedAndCommitted() {
        tradingState.setHalted(true, "test", clock.instant());

        send(SignalMapperTest.envelope(
                SignalMapperTest.signal(UUID.randomUUID().toString(), "BTCUSD", "BUY", "MARKET", 1)));

        awaitCommitted();
        Order order = onlyOrder();
        assertThat(order.state()).isEqualTo(OrderState.RISK_REJECTED);
        assertThat(order.reason()).isEqualTo("HALTED");
    }

    @Test
    void unknownSymbolIsSkipped() {
        send(SignalMapperTest.envelope(
                SignalMapperTest.signal(UUID.randomUUID().toString(), "DOGEUSD", "BUY", "MARKET", 1)));

        awaitCommitted();
        assertThat(orders.list(java.util.Optional.empty())).isEmpty();
    }

    private static void send(byte[] value) {
        try {
            producer.send(new ProducerRecord<>(TOPIC, "BTCUSD", value)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        produced++;
    }

    /** The group has committed every message produced so far: nothing is left to be redelivered. */
    private static void awaitCommitted() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (committedOffset() != produced && System.nanoTime() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertThat(committedOffset()).isEqualTo(produced);
    }

    private static long committedOffset() {
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(PARTITION);
            return offset == null ? 0 : offset.offset();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Order onlyOrder() {
        List<Order> all = orders.list(java.util.Optional.empty());
        assertThat(all).hasSize(1);
        return all.get(0);
    }
}
