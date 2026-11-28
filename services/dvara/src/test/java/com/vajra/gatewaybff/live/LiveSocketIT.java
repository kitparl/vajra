package com.vajra.gatewaybff.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import com.sun.net.httpserver.HttpServer;
import com.vajra.contracts.common.v1.EventEnvelope;
import com.vajra.contracts.events.v1.OrderUpdated;
import com.vajra.gatewaybff.TestDatabase;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real WebSocket client, real Kafka, stubbed core and risk over HTTP. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveSocketIT {

    private static final String UI_ORIGIN = "http://ui.test";
    private static final Map<String, String> CORE_ROUTES = new ConcurrentHashMap<>();
    private static final Map<String, Integer> CORE_DELAY_MS = new ConcurrentHashMap<>();
    private static final Map<String, String> RISK_ROUTES = new ConcurrentHashMap<>();
    private static final AtomicInteger QUOTE_CALLS = new AtomicInteger();
    private static final HttpServer CORE = stub(CORE_ROUTES, QUOTE_CALLS, CORE_DELAY_MS);
    private static final HttpServer RISK = stub(RISK_ROUTES, new AtomicInteger(), new ConcurrentHashMap<>());

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse(TestDatabase.kafkaImage()).asCompatibleSubstituteFor("apache/kafka"));

    private static KafkaProducer<String, byte[]> producer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
        registry.add("vajra.upstream.core-base-url", () -> "http://localhost:" + CORE.getAddress().getPort());
        registry.add("vajra.upstream.risk-base-url", () -> "http://localhost:" + RISK.getAddress().getPort());
        registry.add("vajra.upstream.timeout", () -> "PT1S");
        registry.add("KAFKA_BOOTSTRAP", KAFKA::getBootstrapServers);
        registry.add("vajra.live.quote-interval", () -> "PT0.2S");
        registry.add("vajra.live.risk-interval", () -> "PT0.2S");
    }

    @Value("${local.server.port}")
    int port;

    @BeforeAll
    static void topics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of(
                "bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                            new NewTopic("order.updated", 1, (short) 1),
                            new NewTopic("fill.created", 1, (short) 1),
                            new NewTopic("position.updated", 1, (short) 1)))
                    .all().get();
        }
        producer = new KafkaProducer<>(Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
    }

    @AfterAll
    static void stop() {
        producer.close();
        for (HttpServer server : List.of(CORE, RISK)) {
            server.stop(0);
            ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @BeforeEach
    void reset() {
        CORE_ROUTES.clear();
        CORE_DELAY_MS.clear();
        RISK_ROUTES.clear();
        CORE_ROUTES.put("/api/v1/status", "{\"tradingMode\":\"PAPER\"}");
        CORE_ROUTES.put("/api/v1/quotes", "[{\"symbol\":\"BTCUSD\",\"bid\":\"100000.10\",\"ask\":\"100000.20\"}]");
        RISK_ROUTES.put("/api/v1/kill-switch", "{\"halted\":false,\"events\":[]}");
    }

    @Test
    void handshakeWithoutSessionIs401or403() {
        assertThatThrownBy(() -> connect(null, UI_ORIGIN, new Inbox()))
                .hasMessageMatching("(?s).*\\b40[13]\\b.*");
    }

    @Test
    void handshakeWithWrongOriginIs403() throws Exception {
        String cookie = login();

        assertThatThrownBy(() -> connect(cookie, "http://evil.test", new Inbox())).hasMessageContaining("403");
        assertThatThrownBy(() -> connect(cookie, null, new Inbox())).hasMessageContaining("403");
    }

    @Test
    void helloThenQuotesArriveWhileConnected() throws Exception {
        CORE_DELAY_MS.put("/api/v1/status", 600); // quotes keep flowing while hello waits for the mode
        Inbox inbox = new Inbox();
        WebSocketSession socket = connect(login(), UI_ORIGIN, inbox);

        until(3, () -> inbox.messages.size() >= 3);
        assertThat(inbox.messages.get(0)).isEqualTo("{\"type\":\"hello\",\"data\":{\"tradingMode\":\"PAPER\"}}");
        assertThat(inbox.messages).anyMatch(m -> m.equals(
                "{\"type\":\"quote\",\"data\":[{\"symbol\":\"BTCUSD\",\"bid\":\"100000.10\",\"ask\":\"100000.20\"}]}"));
        socket.close();
    }

    @Test
    void pollersStopWhenNoClients() throws Exception {
        Inbox inbox = new Inbox();
        WebSocketSession socket = connect(login(), UI_ORIGIN, inbox);
        until(3, () -> QUOTE_CALLS.get() > 0);
        socket.close();

        Thread.sleep(1500);
        int settled = QUOTE_CALLS.get();
        Thread.sleep(2000);

        assertThat(QUOTE_CALLS.get()).isEqualTo(settled);
    }

    @Test
    void kafkaOrderUpdatedReachesSocket() throws Exception {
        Inbox inbox = new Inbox();
        WebSocketSession socket = connect(login(), UI_ORIGIN, inbox);
        OrderUpdated order = OrderUpdated.newBuilder()
                .setOrderId("o-1").setClientOrderId("c-1").setVenue("DELTA_TESTNET").setSymbol("BTCUSD")
                .setSide("BUY").setOrderType("LIMIT").setSize(3).setLimitPrice("100000.10")
                .setState("FILLED").setFilledSize(3).setAvgFillPrice("100000.15").setFees("0.0123")
                .setUpdatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L)).build();

        // latest offset: the consumer may not be assigned yet, so keep producing until one lands
        until(20, () -> {
            produce("order.updated", order);
            return inbox.messages.stream().anyMatch(m -> m.contains("\"type\":\"order\""));
        });

        String message = inbox.messages.stream().filter(m -> m.contains("\"type\":\"order\"")).findFirst().orElseThrow();
        assertThat(message)
                .contains("\"orderId\":\"o-1\"", "\"limitPrice\":\"100000.10\"", "\"avgFillPrice\":\"100000.15\"",
                        "\"fees\":\"0.0123\"", "\"size\":3", "\"state\":\"FILLED\"");
        socket.close();
    }

    @Test
    void riskSentOnChangeOnly() throws Exception {
        Inbox inbox = new Inbox();
        WebSocketSession socket = connect(login(), UI_ORIGIN, inbox);
        until(3, () -> riskCount(inbox) >= 1);

        Thread.sleep(1200);
        assertThat(riskCount(inbox)).isEqualTo(1);

        RISK_ROUTES.put("/api/v1/kill-switch", "{\"halted\":true,\"events\":[{\"reason\":\"manual\"}]}");
        until(3, () -> riskCount(inbox) == 2);
        Thread.sleep(800);
        assertThat(riskCount(inbox)).isEqualTo(2);
        assertThat(inbox.messages).anyMatch(m ->
                m.equals("{\"type\":\"risk\",\"data\":{\"halted\":true,\"events\":[{\"reason\":\"manual\"}]}}"));
        socket.close();
    }

    @Test
    void socketClosedWhenSessionInvalidated() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String cookies = login();
        Inbox inbox = new Inbox();
        connect(cookies, UI_ORIGIN, inbox);
        until(3, () -> !inbox.messages.isEmpty());

        HttpResponse<String> logout = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
                .header("Cookie", cookies)
                .header("X-XSRF-TOKEN", cookieValue(cookies, "XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(204);

        until(3, () -> inbox.closed.get() != null);
        assertThat(inbox.closed.get().getCode()).isEqualTo(4401);
        assertThat(inbox.closed.get().getReason()).isEqualTo("session ended");
    }

    /** Polls every 300 ms (the condition may also have side effects, like producing) until true or the deadline. */
    private static void until(int seconds, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + seconds + "s");
            }
            Thread.sleep(300);
        }
    }

    @Test
    void socketClosedAfterSessionIdRotation() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String cookies = login();
        Inbox inbox = new Inbox();
        connect(cookies, UI_ORIGIN, inbox);
        until(3, () -> !inbox.messages.isEmpty());

        String rotated = loginAgain(http, cookies);
        assertThat(cookieValue(rotated, "VAJRA_SESSION")).isNotEqualTo(cookieValue(cookies, "VAJRA_SESSION"));
        Thread.sleep(500);
        assertThat(inbox.closed.get()).as("login alone must not close the socket").isNull();
        HttpResponse<String> logout = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
                .header("Cookie", rotated)
                .header("X-XSRF-TOKEN", cookieValue(rotated, "XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(204);

        until(3, () -> inbox.closed.get() != null);
        assertThat(inbox.closed.get().getCode()).isEqualTo(4401);
    }

    @Test
    void logoutDuringHelloFetchClosesSocket() throws Exception {
        CORE_DELAY_MS.put("/api/v1/status", 800); // hello is still waiting for the mode when the user logs out
        HttpClient http = HttpClient.newHttpClient();
        String cookies = login();
        Inbox inbox = new Inbox();
        connect(cookies, UI_ORIGIN, inbox);

        HttpResponse<String> logout = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
                .header("Cookie", cookies)
                .header("X-XSRF-TOKEN", cookieValue(cookies, "XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(204);

        until(3, () -> inbox.closed.get() != null);
        assertThat(inbox.closed.get().getCode()).isEqualTo(4401);
        Thread.sleep(1500);
        assertThat(inbox.messages).noneMatch(m -> m.contains("\"type\":\"quote\""));
    }

    @Test
    void malformedKafkaRecordDoesNotStopConsumer() throws Exception {
        Inbox inbox = new Inbox();
        WebSocketSession socket = connect(login(), UI_ORIGIN, inbox);
        OrderUpdated order = OrderUpdated.newBuilder().setOrderId("o-2").setState("NEW").build();

        until(20, () -> {
            producer.send(new ProducerRecord<>("order.updated", "k", new byte[] {1, 2, 3, (byte) 0xff, 0x7f}));
            produce("order.updated", order);
            return inbox.messages.stream().anyMatch(m -> m.contains("\"orderId\":\"o-2\""));
        });
        socket.close();
    }

    private String loginAgain(HttpClient http, String cookies) throws Exception {
        Map<String, String> jar = new LinkedHashMap<>();
        for (String pair : cookies.split("; ")) {
            jar.put(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
        }
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("Cookie", cookies)
                .header("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"correct-horse\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(204);
        collect(login, jar);
        return header(jar);
    }

    private static int riskCount(Inbox inbox) {
        return (int) inbox.messages.stream().filter(m -> m.contains("\"type\":\"risk\"")).count();
    }

    private static void produce(String topic, OrderUpdated payload) {
        EventEnvelope envelope = EventEnvelope.newBuilder()
                .setEventId(UUID.randomUUID().toString()).setEventType(topic).setProducer("core-service")
                .setPayload(Any.pack(payload)).build();
        producer.send(new ProducerRecord<>(topic, "k", envelope.toByteArray()));
        producer.flush();
    }

    private WebSocketSession connect(String cookies, String origin, Inbox inbox) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (cookies != null) {
            headers.add("Cookie", cookies);
        }
        if (origin != null) {
            headers.setOrigin(origin);
        }
        return new StandardWebSocketClient()
                .execute(inbox, headers, URI.create("ws://localhost:" + port + "/ws"))
                .get(5, TimeUnit.SECONDS);
    }

    /** CSRF bootstrap plus JSON login; returns a Cookie header value (VAJRA_SESSION and XSRF-TOKEN). */
    private String login() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        Map<String, String> cookies = new LinkedHashMap<>();
        HttpResponse<String> csrf = http.send(
                HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        collect(csrf, cookies);
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("Cookie", header(cookies))
                .header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"correct-horse\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(204);
        collect(login, cookies);
        assertThat(cookies).containsKey("VAJRA_SESSION");
        return header(cookies);
    }

    private static void collect(HttpResponse<?> response, Map<String, String> cookies) {
        for (String set : response.headers().allValues("Set-Cookie")) {
            String pair = set.substring(0, set.indexOf(';') < 0 ? set.length() : set.indexOf(';'));
            int eq = pair.indexOf('=');
            cookies.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
    }

    private static String header(Map<String, String> cookies) {
        StringBuilder out = new StringBuilder();
        cookies.forEach((k, v) -> out.append(out.isEmpty() ? "" : "; ").append(k).append('=').append(v));
        return out.toString();
    }

    private static String cookieValue(String header, String name) {
        for (String pair : header.split("; ")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        throw new IllegalStateException("no cookie " + name);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static final class Inbox extends TextWebSocketHandler {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final AtomicReference<CloseStatus> closed = new AtomicReference<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.set(status);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer stub(
            Map<String, String> routes, AtomicInteger quoteCalls, Map<String, Integer> delays) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", freePort()), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/api/v1/quotes")) {
                    quoteCalls.incrementAndGet();
                }
                try {
                    Thread.sleep(delays.getOrDefault(path, 0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String body = routes.get(path);
                byte[] bytes = (body == null ? "{\"error\":\"NO_STUB\"}" : body).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
