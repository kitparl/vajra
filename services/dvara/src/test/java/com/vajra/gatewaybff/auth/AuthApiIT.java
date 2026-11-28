package com.vajra.gatewaybff.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.vajra.gatewaybff.GatewayBffApplication;
import com.vajra.gatewaybff.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthApiIT {

    private static final String PASSWORD = "correct-horse";

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
        registry.add("vajra.upstream.core-base-url", () -> "http://localhost:1");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LoginLockout lockout;

    @MockitoSpyBean
    AuditEventRepository auditRepository;

    @BeforeEach
    void cleanState() {
        lockout.recordSuccess();
        jdbc.update("delete from audit_event");
    }

    @Test
    void healthIsPublicEverythingElseNeedsSession() throws Exception {
        Client client = new Client(port);

        assertThat(client.get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(client.get("/api/v1/session").statusCode()).isEqualTo(401);
        assertThat(client.get("/api/v1/anything").statusCode()).isEqualTo(401);
        assertThat(client.get("/actuator/env").statusCode()).isIn(401, 404);
    }

    @Test
    void loginWithoutCsrfIs403() throws Exception {
        Client client = new Client(port);

        assertThat(client.postJson("/api/v1/auth/login", login("admin", PASSWORD), false).statusCode())
                .isEqualTo(403);
    }

    @Test
    void loginSuccessThenSessionShowsUserAndMode() throws Exception {
        Client client = new Client(port);
        client.fetchCsrf();

        HttpResponse<String> login = client.postJson("/api/v1/auth/login", login("admin", PASSWORD), true);
        assertThat(login.statusCode()).isEqualTo(204);

        HttpResponse<String> session = client.get("/api/v1/session");
        assertThat(session.statusCode()).isEqualTo(200);
        assertThat(session.body()).contains("\"username\":\"admin\"").contains("\"tradingMode\":\"UNKNOWN\"");
        assertThat(auditKinds()).containsExactly("LOGIN_OK");
    }

    @Test
    void wrongPasswordIs401AndAudited() throws Exception {
        Client client = new Client(port);
        client.fetchCsrf();

        HttpResponse<String> response = client.postJson("/api/v1/auth/login", login("admin", "wrong-pass"), true);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"error\":\"BAD_CREDENTIALS\"");
        assertThat(auditKinds()).containsExactly("LOGIN_FAILED");
        String detail = jdbc.queryForObject("select detail::text from audit_event", String.class);
        assertThat(detail).doesNotContain("wrong-pass").contains("admin");
        assertThat(client.get("/api/v1/session").statusCode()).isEqualTo(401);
    }

    @Test
    void sixthAttemptIs429EvenWithRightPassword() throws Exception {
        Client client = new Client(port);
        client.fetchCsrf();
        for (int i = 0; i < 5; i++) {
            assertThat(client.postJson("/api/v1/auth/login", login("admin", "wrong-pass"), true).statusCode())
                    .isEqualTo(401);
        }

        HttpResponse<String> response = client.postJson("/api/v1/auth/login", login("admin", PASSWORD), true);

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("\"error\":\"LOCKED\"").contains("\"retryAfterSeconds\":");
        assertThat(auditKinds()).contains("LOGIN_LOCKED").doesNotContain("LOGIN_OK");
    }

    @Test
    void logoutEndsSession() throws Exception {
        Client client = new Client(port);
        client.fetchCsrf();
        client.postJson("/api/v1/auth/login", login("admin", PASSWORD), true);
        assertThat(client.get("/api/v1/session").statusCode()).isEqualTo(200);

        assertThat(client.postJson("/api/v1/auth/logout", "", true).statusCode()).isEqualTo(204);

        assertThat(client.get("/api/v1/session").statusCode()).isEqualTo(401);
        assertThat(auditKinds()).containsExactly("LOGIN_OK", "LOGOUT");
    }

    @Test
    void secureCookieFlagFollowsConfig() throws Exception {
        String insecure = loginSetCookies("false");
        assertThat(insecure).contains("VAJRA_SESSION=").doesNotContain("Secure");

        String secure = loginSetCookies("true");
        assertThat(secure).contains("VAJRA_SESSION=", "XSRF-TOKEN=");
        assertThat(setCookie(secure, "VAJRA_SESSION")).contains("Secure", "HttpOnly", "SameSite=Strict");
        assertThat(setCookie(secure, "XSRF-TOKEN")).contains("Secure", "SameSite=Strict", "Path=/");
    }

    @Test
    void fifthFailureWritesLoginLocked() throws Exception {
        Client client = new Client(port);
        client.fetchCsrf();
        for (int i = 0; i < 5; i++) {
            client.postJson("/api/v1/auth/login", login("admin", "wrong-pass"), true);
        }

        assertThat(auditKinds()).containsExactly(
                "LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_LOCKED");
    }

    @Test
    void parallelWrongLoginsGetAtMostFiveAttempts() throws Exception {
        List<Client> clients = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Client client = new Client(port);
            client.fetchCsrf();
            clients.add(client);
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Client client : clients) {
                futures.add(pool.submit(() ->
                        client.postJson("/api/v1/auth/login", login("admin", "wrong-pass"), true).statusCode()));
            }
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
        }

        assertThat(statuses).filteredOn(status -> status == 401).hasSize(5);
        assertThat(statuses).filteredOn(status -> status == 429).hasSize(5);
        assertThat(auditKinds().stream().filter("LOGIN_FAILED"::equals).count()).isEqualTo(5);
    }

    @Test
    void auditFailureOnLoginMeansNoSession() throws Exception {
        doThrow(new IllegalStateException("audit down")).when(auditRepository).record(eq("LOGIN_OK"), any());
        Client client = new Client(port);
        client.fetchCsrf();

        assertThat(client.postJson("/api/v1/auth/login", login("admin", PASSWORD), true).statusCode())
                .isEqualTo(500);

        assertThat(client.get("/api/v1/session").statusCode()).isEqualTo(401);
    }

    @Test
    void auditFailureOnLogoutStillEndsSession() throws Exception {
        Client client = new Client(port);
        client.fetchCsrf();
        client.postJson("/api/v1/auth/login", login("admin", PASSWORD), true);
        doThrow(new IllegalStateException("audit down")).when(auditRepository).record(eq("LOGOUT"), any());

        client.postJson("/api/v1/auth/logout", "", true);

        assertThat(client.get("/api/v1/session").statusCode()).isEqualTo(401);
    }

    @Test
    void unauthenticatedGetCreatesNoSession() throws Exception {
        Client client = new Client(port);

        HttpResponse<String> response = client.get("/api/v1/session", "*/*");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(client.setCookieHeaders()).noneMatch(header -> header.startsWith("VAJRA_SESSION="));
    }

    @Test
    void loginRotatesSessionIdAndCsrfToken() throws Exception {
        Client client = new Client(port);
        client.fetchCsrf();
        client.setCookie("VAJRA_SESSION", "ATTACKERCHOSENSESSIONID");
        String csrfBefore = client.cookie("XSRF-TOKEN");

        assertThat(client.postJson("/api/v1/auth/login", login("admin", PASSWORD), true).statusCode())
                .isEqualTo(204);

        assertThat(client.cookie("VAJRA_SESSION")).isNotEqualTo("ATTACKERCHOSENSESSIONID");
        assertThat(client.cookie("XSRF-TOKEN")).isNotEqualTo(csrfBefore);
        assertThat(client.get("/api/v1/session").statusCode()).isEqualTo(200);
    }

    @Test
    void logoutWithoutCsrfIs403() throws Exception {
        Client client = new Client(port);
        client.fetchCsrf();
        client.postJson("/api/v1/auth/login", login("admin", PASSWORD), true);

        assertThat(client.postJson("/api/v1/auth/logout", "", false).statusCode()).isEqualTo(403);
        assertThat(client.get("/api/v1/session").statusCode()).isEqualTo(200);
    }

    @Test
    void lockedResponseCarriesRetryAfterHeader() throws Exception {
        Client client = new Client(port);
        client.fetchCsrf();
        for (int i = 0; i < 5; i++) {
            client.postJson("/api/v1/auth/login", login("admin", "wrong-pass"), true);
        }

        HttpResponse<String> response = client.postJson("/api/v1/auth/login", login("admin", PASSWORD), true);

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow())).isBetween(1, 900);
    }

    @Test
    void envStyleCookieSecureFalseAppliesToBothCookies() throws Exception {
        try (ConfigurableApplicationContext context = otherApp()
                .run("--server.port=0", "--vajra.auth.cookie-secure=${GATEWAY_COOKIE_SECURE:true}",
                        "--GATEWAY_COOKIE_SECURE=false")) {
            Client client = new Client(context.getEnvironment().getProperty("local.server.port", Integer.class));
            client.fetchCsrf();
            client.postJson("/api/v1/auth/login", login("admin", PASSWORD), true);

            assertThat(client.setCookieHeaders()).hasSizeGreaterThanOrEqualTo(2).noneMatch(header -> header.contains("Secure"));
        }
    }

    @Test
    void missingConfiguredHashFailsStartup() {
        assertThatThrownBy(() -> otherApp().run("--vajra.auth.password-hash="))
                .hasStackTraceContaining("vajra.auth.password-hash");
    }

    private String loginSetCookies(String cookieSecure) throws Exception {
        try (ConfigurableApplicationContext context = otherApp().run("--server.port=0", "--vajra.auth.cookie-secure=" + cookieSecure)) {
            int otherPort = context.getEnvironment().getProperty("local.server.port", Integer.class);
            Client client = new Client(otherPort);
            client.fetchCsrf();
            client.postJson("/api/v1/auth/login", login("admin", PASSWORD), true);
            return String.join("\n", client.setCookieHeaders());
        }
    }

    private static String setCookie(String all, String name) {
        return all.lines().filter(line -> line.startsWith(name + "=")).findFirst().orElseThrow();
    }

    /** A second application instance on the same database, for settings that differ from the shared context. */
    private static SpringApplicationBuilder otherApp() {
        return new SpringApplicationBuilder(GatewayBffApplication.class)
                .properties("DB_URL=" + POSTGRES.getJdbcUrl(), "DB_USER=" + TestDatabase.ROLE, "DB_PASSWORD=test-pw")
                .properties("spring.main.banner-mode=off");
    }

    private List<String> auditKinds() {
        return jdbc.queryForList("select kind from audit_event order by id", String.class);
    }

    private static String login(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    /** Tiny cookie-keeping HTTP client; records every Set-Cookie header it sees. */
    private static final class Client {
        private final HttpClient http = HttpClient.newHttpClient();
        private final Map<String, String> cookies = new LinkedHashMap<>();
        private final List<String> setCookieHeaders = new java.util.ArrayList<>();
        private final int port;

        Client(int port) {
            this.port = port;
        }

        List<String> setCookieHeaders() {
            return setCookieHeaders;
        }

        void fetchCsrf() throws Exception {
            assertThat(get("/api/v1/auth/csrf").statusCode()).isEqualTo(204);
            assertThat(cookies).containsKey("XSRF-TOKEN");
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(builder(path).GET());
        }

        HttpResponse<String> get(String path, String accept) throws Exception {
            return send(builder(path).header("Accept", accept).GET());
        }

        String cookie(String name) {
            return cookies.get(name);
        }

        void setCookie(String name, String value) {
            cookies.put(name, value);
        }

        HttpResponse<String> postJson(String path, String body, boolean withCsrf) throws Exception {
            HttpRequest.Builder request = builder(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (withCsrf) {
                request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            }
            return send(request);
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
                setCookieHeaders.add(header);
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
