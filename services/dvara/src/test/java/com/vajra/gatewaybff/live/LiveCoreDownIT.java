package com.vajra.gatewaybff.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.vajra.gatewaybff.TestDatabase;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Core and risk are unreachable (closed ports): the socket must still open and say tradingMode UNKNOWN. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveCoreDownIT {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
        registry.add("vajra.upstream.core-base-url", () -> "http://localhost:" + closedPort());
        registry.add("vajra.upstream.risk-base-url", () -> "http://localhost:" + closedPort());
        registry.add("KAFKA_BOOTSTRAP", () -> "localhost:" + closedPort());
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void helloSaysUnknownWhenCoreDown() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> csrf = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).build(),
                HttpResponse.BodyHandlers.ofString());
        StringBuilder cookies = new StringBuilder();
        String xsrf = null;
        for (String set : csrf.headers().allValues("Set-Cookie")) {
            String pair = set.substring(0, set.indexOf(';'));
            cookies.append(cookies.isEmpty() ? "" : "; ").append(pair);
            if (pair.startsWith("XSRF-TOKEN=")) {
                xsrf = pair.substring("XSRF-TOKEN=".length());
            }
        }
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("Cookie", cookies.toString())
                .header("X-XSRF-TOKEN", xsrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"correct-horse\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        String session = login.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("VAJRA_SESSION=")).map(c -> c.substring(0, c.indexOf(';'))).findFirst()
                .orElseThrow();
        List<String> messages = new CopyOnWriteArrayList<>();
        AtomicReference<CloseStatus> closed = new AtomicReference<>();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Cookie", session);
        headers.setOrigin("http://ui.test");

        WebSocketSession socket = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                messages.add(message.getPayload());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
                closed.set(status);
            }
        }, headers, URI.create("ws://localhost:" + port + "/ws")).get(5, java.util.concurrent.TimeUnit.SECONDS);

        for (int i = 0; i < 50 && messages.isEmpty(); i++) {
            Thread.sleep(100);
        }
        Thread.sleep(500);
        assertThat(messages.get(0)).isEqualTo("{\"type\":\"hello\",\"data\":{\"tradingMode\":\"UNKNOWN\"}}");
        assertThat(closed.get()).isNull();
        assertThat(socket.isOpen()).isTrue();
        socket.close();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
