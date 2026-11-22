package com.vajra.coreservice.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.vajra.contracts.events.v1.OrderUpdated;
import com.vajra.coreservice.testsupport.MutableClock;
import com.vajra.coreservice.testsupport.TestDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class OutboxPublisherIT {

    private static final String TOPIC = "order.updated";

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse(TestDatabase.kafkaImage())
            .asCompatibleSubstituteFor("apache/kafka"));

    static DataSource dataSource;
    static JdbcClient jdbc;
    static MutableClock clock;
    static AuditWriter audit;
    static AuditEventRepository repository;
    static TransactionTemplate tx;

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = TestDatabase.migratedDataSource(POSTGRES);
        jdbc = JdbcClient.create(dataSource);
        repository = new AuditEventRepository(jdbc);
        clock = new MutableClock(Instant.parse("2026-11-22T10:00:00Z"));
        audit = new AuditWriter(jdbc, clock);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        }
    }

    @BeforeEach
    void clean() {
        jdbc.sql("delete from audit_event").update();
    }

    @Test
    void publishesUnpublishedRowsInOrderWithKey() {
        write("BTCUSD", "ETHUSD", "BTCUSD");

        int sent = publisher(KAFKA.getBootstrapServers()).publishBatch();

        assertThat(sent).isEqualTo(3);
        List<byte[]> payloads = jdbc.sql("select payload from audit_event order by occurred_at, id")
                .query(byte[].class).list();
        List<ConsumerRecord<String, byte[]>> records = consume(payloads);
        assertThat(records).extracting(ConsumerRecord::key).containsExactly("BTCUSD", "ETHUSD", "BTCUSD");
        for (int i = 0; i < 3; i++) {
            assertThat(records.get(i).value()).isEqualTo(payloads.get(i));
        }
        assertThat(jdbc.sql("select count(*) from audit_event where published_at is null")
                .query(Long.class).single()).isZero();
    }

    @Test
    void outboxKeepsRowsWhileKafkaDownThenPublishes() {
        write("BTCUSD", "ETHUSD");

        long begin = System.nanoTime();
        int sent = publisher("localhost:1").publishBatch();

        assertThat(sent).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(15));
        assertThat(jdbc.sql("select count(*) from audit_event where published_at is null")
                .query(Long.class).single()).isEqualTo(2L);

        assertThat(publisher(KAFKA.getBootstrapServers()).publishBatch()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from audit_event where published_at is null")
                .query(Long.class).single()).isZero();
    }

    @Test
    void noRowsIsNoop() {
        assertThat(publisher(KAFKA.getBootstrapServers()).publishBatch()).isZero();
    }

    private static void write(String... keys) {
        tx.executeWithoutResult(s -> {
            for (String key : keys) {
                clock.advance(Duration.ofSeconds(1));
                audit.record(TOPIC, key, OrderUpdated.newBuilder().setOrderId(UUID.randomUUID().toString()).build());
            }
        });
    }

    private static OutboxPublisher publisher(String bootstrap) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        KafkaTemplate<String, byte[]> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
        return new OutboxPublisher(repository, template, java.time.Clock.systemUTC(), 100);
    }

    private static List<ConsumerRecord<String, byte[]>> consume(List<byte[]> expected) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (records.size() < expected.size() && System.nanoTime() < deadline) {
                // The topic is shared by all tests in this class: keep only this test's rows.
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (expected.stream().anyMatch(p -> java.util.Arrays.equals(p, r.value()))) {
                        records.add(r);
                    }
                });
            }
        }
        return records;
    }
}
