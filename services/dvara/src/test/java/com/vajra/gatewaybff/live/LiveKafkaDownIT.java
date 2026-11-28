package com.vajra.gatewaybff.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.vajra.gatewaybff.TestDatabase;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Kafka bootstrap points at a closed port: the context must still start and REST must keep working. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveKafkaDownIT {

    private static final HttpServer CORE = stub();

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
        registry.add("vajra.upstream.core-base-url", () -> "http://localhost:" + CORE.getAddress().getPort());
        registry.add("KAFKA_BOOTSTRAP", () -> "localhost:" + closedPort());
    }

    @Value("${local.server.port}")
    int port;

    @AfterAll
    static void stop() {
        CORE.stop(0);
    }

    @Test
    void startsAndServesRestWithKafkaDown() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> csrf = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).build(),
                HttpResponse.BodyHandlers.ofString());
        String xsrf = null;
        StringBuilder cookies = new StringBuilder();
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
        assertThat(login.statusCode()).isEqualTo(204);
        String session = login.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("VAJRA_SESSION=")).map(c -> c.substring(0, c.indexOf(';'))).findFirst()
                .orElseThrow();

        HttpResponse<String> positions = http.send(HttpRequest.newBuilder(uri("/api/v1/positions"))
                .header("Cookie", session).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(positions.statusCode()).isEqualTo(200);
        assertThat(positions.body()).isEqualTo("[]");
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

    private static HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", closedPort()), 0);
            server.createContext("/", exchange -> {
                byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
