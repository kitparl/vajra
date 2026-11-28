package com.vajra.gatewaybff.api;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Gateway against real HTTP stubs of core and risk (same-port restart simulates "core down"). */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadApiIT {

    private static final String RISK_TOKEN = "risk-token-for-test";
    private static final int CORE_PORT = freePort();
    private static final Map<String, StubResponse> CORE_ROUTES = new ConcurrentHashMap<>();
    private static final List<String> CORE_REQUESTS = new CopyOnWriteArrayList<>();
    private static final Map<String, StubResponse> RISK_ROUTES = new ConcurrentHashMap<>();
    private static final List<String> RISK_AUTH_HEADERS = new CopyOnWriteArrayList<>();
    private static final HttpServer RISK = start(freePort(), RISK_ROUTES, RISK_AUTH_HEADERS);
    private static volatile HttpServer core = start(CORE_PORT, CORE_ROUTES, CORE_REQUESTS);

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
        registry.add("vajra.upstream.core-base-url", () -> "http://localhost:" + CORE_PORT);
        registry.add("vajra.upstream.risk-base-url", () -> "http://localhost:" + RISK.getAddress().getPort());
        registry.add("vajra.upstream.risk-api-token", () -> RISK_TOKEN);
        registry.add("vajra.upstream.timeout", () -> "PT1S");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() throws IOException {
        CORE_ROUTES.clear();
        CORE_REQUESTS.clear();
        RISK_ROUTES.clear();
        RISK_AUTH_HEADERS.clear();
        if (core == null) {
            core = start(CORE_PORT, CORE_ROUTES, CORE_REQUESTS);
        }
        jdbc.update("delete from audit_event");
    }

    @AfterAll
    static void stop() {
        RISK.stop(0);
        if (core != null) {
            core.stop(0);
        }
    }

    @Test
    void readEndpointsNeedSession() throws Exception {
        Client client = new Client(port);
        for (String path : List.of(
                "/api/v1/instruments", "/api/v1/quotes", "/api/v1/positions", "/api/v1/pnl", "/api/v1/orders",
                "/api/v1/orders/abc", "/api/v1/candles?symbol=BTCUSD&from=a&to=b", "/api/v1/risk/status",
                "/api/v1/risk/limits", "/api/v1/risk/rejections")) {
            assertThat(client.get(path).statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    void forwardsPositionsVerbatim() throws Exception {
        String body = "[{\"symbol\":\"BTCUSD\",\"qty\":\"0.5\",\"avgPrice\":\"100000.10\"}]";
        CORE_ROUTES.put("/api/v1/positions", new StubResponse(200, body));
        Client client = loggedIn();

        HttpResponse<String> response = client.get("/api/v1/positions");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);
    }

    @Test
    void instrumentsFilteredToTradingEnabled() throws Exception {
        CORE_ROUTES.put("/api/v1/instruments", new StubResponse(200,
                "[{\"symbol\":\"BTCUSD\",\"tradingEnabled\":true},{\"symbol\":\"DOGEUSD\",\"tradingEnabled\":false}]"));
        Client client = loggedIn();

        HttpResponse<String> response = client.get("/api/v1/instruments");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("BTCUSD").doesNotContain("DOGEUSD");
    }

    @Test
    void queryParamsPassThrough() throws Exception {
        CORE_ROUTES.put("/api/v1/orders?state=FILLED", new StubResponse(200, "[{\"id\":\"1\"}]"));
        CORE_ROUTES.put(
                "/api/v1/candles?symbol=BTCUSD&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z",
                new StubResponse(200, "[{\"o\":\"1\"}]"));
        Client client = loggedIn();

        assertThat(client.get("/api/v1/orders?state=FILLED").body()).isEqualTo("[{\"id\":\"1\"}]");
        assertThat(client.get("/api/v1/candles?symbol=BTCUSD&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z")
                        .body())
                .isEqualTo("[{\"o\":\"1\"}]");
        assertThat(client.get("/api/v1/candles?symbol=BTCUSD").statusCode()).isEqualTo(400);
    }

    @Test
    void riskCallsCarryBearerToken() throws Exception {
        RISK_ROUTES.put("/api/v1/kill-switch", new StubResponse(200, "{\"halted\":false,\"events\":[]}"));
        RISK_ROUTES.put("/api/v1/limits", new StubResponse(200, "[]"));
        RISK_ROUTES.put("/api/v1/rejections?limit=5", new StubResponse(200, "[]"));
        Client client = loggedIn();

        assertThat(client.get("/api/v1/risk/status").body()).isEqualTo("{\"halted\":false,\"events\":[]}");
        assertThat(client.get("/api/v1/risk/limits").statusCode()).isEqualTo(200);
        assertThat(client.get("/api/v1/risk/rejections?limit=5").statusCode()).isEqualTo(200);

        assertThat(RISK_AUTH_HEADERS).hasSize(3).allMatch(("Bearer " + RISK_TOKEN)::equals);
    }

    @Test
    void upstream4xxPassesThrough() throws Exception {
        String body = "{\"error\":\"NOT_FOUND\",\"message\":\"order not found\"}";
        CORE_ROUTES.put("/api/v1/orders/missing", new StubResponse(404, body));
        RISK_ROUTES.put("/api/v1/rejections?limit=999", new StubResponse(400, "{\"error\":\"BAD_REQUEST\"}"));
        Client client = loggedIn();

        HttpResponse<String> notFound = client.get("/api/v1/orders/missing");
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(notFound.body()).isEqualTo(body);
        HttpResponse<String> badLimit = client.get("/api/v1/risk/rejections?limit=999");
        assertThat(badLimit.statusCode()).isEqualTo(400);
        assertThat(badLimit.body()).isEqualTo("{\"error\":\"BAD_REQUEST\"}");
    }

    @Test
    void upstream5xxGives503() throws Exception {
        RISK_ROUTES.put("/api/v1/limits", new StubResponse(500, "{\"error\":\"boom\"}"));
        Client client = loggedIn();

        HttpResponse<String> response = client.get("/api/v1/risk/limits");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"error\":\"UPSTREAM_UNAVAILABLE\",\"upstream\":\"risk\"}");
    }

    @Test
    void upstream401MapsTo503() throws Exception {
        RISK_ROUTES.put("/api/v1/limits", new StubResponse(401, "{\"error\":\"UNAUTHORIZED\"}"));
        CORE_ROUTES.put("/api/v1/pnl", new StubResponse(403, "{}"));
        Client client = loggedIn();

        HttpResponse<String> risk = client.get("/api/v1/risk/limits");
        assertThat(risk.statusCode()).isEqualTo(503);
        assertThat(risk.body()).isEqualTo("{\"error\":\"UPSTREAM_UNAVAILABLE\",\"upstream\":\"risk\"}");
        HttpResponse<String> core = client.get("/api/v1/pnl");
        assertThat(core.statusCode()).isEqualTo(503);
        assertThat(core.body()).contains("\"upstream\":\"core\"");
    }

    @Test
    void sessionModeUnknownWhenCoreReturnsEmptyBody() throws Exception {
        CORE_ROUTES.put("/api/v1/status", new StubResponse(200, ""));
        Client client = loggedIn();

        HttpResponse<String> response = client.get("/api/v1/session");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"tradingMode\":\"UNKNOWN\"");
    }

    @Test
    void emptyUpstreamBodyOnReadEndpointGives503() throws Exception {
        CORE_ROUTES.put("/api/v1/instruments", new StubResponse(200, ""));
        CORE_ROUTES.put("/api/v1/positions", new StubResponse(200, "null"));
        Client client = loggedIn();

        assertThat(client.get("/api/v1/instruments").statusCode()).isEqualTo(503);
        assertThat(client.get("/api/v1/positions").statusCode()).isEqualTo(503);
    }

    @Test
    void pathAndQueryValuesAreEncoded() throws Exception {
        Client client = loggedIn();

        client.get("/api/v1/candles?symbol=A%26B&from=x&to=y");
        client.get("/api/v1/orders/a%3Fb%3Dc");

        assertThat(CORE_REQUESTS.get(0)).startsWith("/api/v1/candles?symbol=A%26B&from=x&to=y");
        assertThat(CORE_REQUESTS.get(1)).isEqualTo("/api/v1/orders/a%3Fb%3Dc");
    }

    @Test
    void coreDownGives503Json() throws Exception {
        Client client = loggedIn();
        core.stop(0);
        core = null;

        HttpResponse<String> response = client.get("/api/v1/positions");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"error\":\"UPSTREAM_UNAVAILABLE\",\"upstream\":\"core\"}");
    }

    @Test
    void coreTimeoutGives503Json() throws Exception {
        CORE_ROUTES.put("/api/v1/pnl", new StubResponse(200, "{}", 2500));
        Client client = loggedIn();

        HttpResponse<String> response = client.get("/api/v1/pnl");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"error\":\"UPSTREAM_UNAVAILABLE\",\"upstream\":\"core\"}");
    }

    @Test
    void sessionModeComesFromCore() throws Exception {
        CORE_ROUTES.put("/api/v1/status", new StubResponse(200, "{\"tradingMode\":\"LIVE\"}"));
        Client client = loggedIn();

        assertThat(client.get("/api/v1/session").body()).contains("\"tradingMode\":\"LIVE\"");
    }

    @Test
    void sessionModeUnknownWhenCoreDown() throws Exception {
        Client client = loggedIn();
        core.stop(0);
        core = null;

        HttpResponse<String> response = client.get("/api/v1/session");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"tradingMode\":\"UNKNOWN\"");
    }

    @Test
    void postToReadEndpointIsRejected() throws Exception {
        Client client = loggedIn();

        HttpResponse<String> response = client.post("/api/v1/positions");

        assertThat(response.statusCode()).isIn(403, 405);
    }

    private Client loggedIn() throws Exception {
        Client client = new Client(port);
        assertThat(client.get("/api/v1/auth/csrf").statusCode()).isEqualTo(204);
        HttpResponse<String> login = client.post(
                "/api/v1/auth/login", "{\"username\":\"admin\",\"password\":\"correct-horse\"}");
        assertThat(login.statusCode()).isEqualTo(204);
        return client;
    }

    record StubResponse(int status, String body, long delayMillis) {
        StubResponse(int status, String body) {
            this(status, body, 0);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer start(int port, Map<String, StubResponse> routes, List<String> authHeaders) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            server.createContext("/", exchange -> {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                authHeaders.add(authHeaders == CORE_REQUESTS
                        ? exchange.getRequestURI().getRawPath()
                                + (exchange.getRequestURI().getRawQuery() == null
                                        ? ""
                                        : "?" + exchange.getRequestURI().getRawQuery())
                        : String.valueOf(authorization));
                String key = exchange.getRequestURI().getPath()
                        + (exchange.getRequestURI().getQuery() == null
                                ? ""
                                : "?" + exchange.getRequestURI().getQuery());
                StubResponse stub = routes.getOrDefault(key, new StubResponse(404, "{\"error\":\"NO_STUB\"}"));
                try {
                    Thread.sleep(stub.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] bytes = stub.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(stub.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) {
                    // client already timed out
                } finally {
                    exchange.close();
                }
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Client {
        private final HttpClient http = HttpClient.newHttpClient();
        private final Map<String, String> cookies = new java.util.LinkedHashMap<>();
        private final int port;

        Client(int port) {
            this.port = port;
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(builder(path).GET());
        }

        HttpResponse<String> post(String path) throws Exception {
            return post(path, "{}");
        }

        HttpResponse<String> post(String path, String body) throws Exception {
            return send(builder(path)
                    .header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", String.valueOf(cookies.get("XSRF-TOKEN")))
                    .POST(HttpRequest.BodyPublishers.ofString(body)));
        }

        private HttpRequest.Builder builder(String path) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (!cookies.isEmpty()) {
                StringBuilder header = new StringBuilder();
                cookies.forEach((k, v) -> header.append(header.isEmpty() ? "" : "; ").append(k).append('=').append(v));
                request.header("Cookie", header.toString());
            }
            return request;
        }

        private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                String pair = header.substring(0, header.indexOf(';') < 0 ? header.length() : header.indexOf(';'));
                int eq = pair.indexOf('=');
                String value = pair.substring(eq + 1);
                if (value.isEmpty()) {
                    cookies.remove(pair.substring(0, eq));
                } else {
                    cookies.put(pair.substring(0, eq), value);
                }
            }
            return response;
        }
    }
}
