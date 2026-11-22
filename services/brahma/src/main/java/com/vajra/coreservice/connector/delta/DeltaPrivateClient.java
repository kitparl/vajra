package com.vajra.coreservice.connector.delta;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signed (private) Delta Exchange India REST calls. Testnet only. Every call is rate-limit guarded and signed; errors
 * become {@link DeltaApiException} without ever carrying a header value or a request body.
 */
public class DeltaPrivateClient {

    private static final int PAGE_SIZE = 100;

    private final RestClient rest;
    private final DeltaRateLimitGuard guard;
    private final DeltaCredentials credentials;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public DeltaPrivateClient(RestClient.Builder builder, DeltaRateLimitGuard guard, DeltaCredentials credentials, Clock clock) {
        this.rest = builder.baseUrl(DeltaPublicClient.TESTNET_BASE_URL).defaultHeader(HttpHeaders.USER_AGENT, "vajra").build();
        this.guard = guard;
        this.credentials = credentials;
        this.clock = clock;
    }

    /** Order request body. {@code limitPrice} is a string and is omitted for market orders. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlaceOrderBody(
            @JsonProperty("product_id") long productId,
            @JsonProperty("size") long size,
            @JsonProperty("side") String side,
            @JsonProperty("order_type") String orderType,
            @JsonProperty("limit_price") String limitPrice,
            @JsonProperty("time_in_force") String timeInForce,
            @JsonProperty("reduce_only") boolean reduceOnly,
            @JsonProperty("client_order_id") String clientOrderId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CancelBody(@JsonProperty("id") long id, @JsonProperty("product_id") long productId) {}

    public DeltaOrder placeOrder(PlaceOrderBody body) {
        JsonNode response = send(HttpMethod.POST, "/v2/orders", "", json(body));
        return mapped(() -> toOrder(response.path("result")));
    }

    public DeltaOrder cancelOrder(long venueOrderId, long productId) {
        JsonNode response = send(HttpMethod.DELETE, "/v2/orders", "", json(new CancelBody(venueOrderId, productId)));
        return mapped(() -> toOrder(response.path("result")));
    }

    /** Empty when Delta does not know the id (HTTP 404 or an order-not-found error). */
    public Optional<DeltaOrder> orderByClientId(String clientOrderId) {
        try {
            JsonNode response = send(HttpMethod.GET, "/v2/orders/client_order_id/" + clientOrderId, "", "");
            return Optional.of(mapped(() -> toOrder(response.path("result"))));
        } catch (DeltaApiException e) {
            if (e.status() == 404 || e.code().contains("not_found")) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** All fills since {@code from}, following the {@code after} cursor. */
    public List<DeltaFill> fills(Instant from) {
        List<DeltaFill> fills = new ArrayList<>();
        String cursor = null;
        do {
            String query = "?start_time=" + toMicros(from) + "&page_size=" + PAGE_SIZE
                    + (cursor == null ? "" : "&after=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            JsonNode page = send(HttpMethod.GET, "/v2/fills", query, "");
            mapped(() -> page.path("result").forEach(node -> fills.add(toFill(node))));
            JsonNode after = page.path("meta").path("after");
            cursor = after.isString() && !after.asString().isBlank() ? after.asString() : null;
        } while (cursor != null);
        return fills;
    }

    public DeltaPosition position(long productId) {
        JsonNode result = send(HttpMethod.GET, "/v2/positions", "?product_id=" + productId, "").path("result");
        return mapped(() -> new DeltaPosition(result.path("size").asLong(0), decimal(result, "entry_price")));
    }

    /** Cheap signed call used at startup to prove the key, secret and clock are accepted. */
    public void authCheck() {
        send(HttpMethod.GET, "/v2/wallet/balances", "", "");
    }

    private JsonNode send(HttpMethod method, String path, String query, String body) {
        if (!credentials.isPresent()) {
            throw new DeltaApiException("Delta credentials are missing or malformed", false, "NO_CREDENTIALS", false, 0, null);
        }
        if (method != HttpMethod.DELETE) { // cancels are never blocked: Delta does not rate-limit them
            guard.checkAllowed();
        }
        long timestamp = clock.instant().getEpochSecond();
        String signature = DeltaSigner.sign(credentials.apiSecret(), method.name(), timestamp, path, query, body);
        RestClient.RequestBodySpec request = rest.method(method)
                .uri(URI.create(DeltaPublicClient.TESTNET_BASE_URL + path + query))
                .header("api-key", credentials.apiKey())
                .header("timestamp", Long.toString(timestamp))
                .header("signature", signature);
        if (!body.isEmpty()) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return guarded(() -> request.retrieve().body(String.class));
    }

    private JsonNode guarded(Supplier<String> call) {
        try {
            String text = call.get();
            JsonNode root = text == null || text.isBlank() ? null : mapper.readTree(text);
            if (root == null || root.isMissingNode()) {
                throw new DeltaApiException("Delta returned an empty body", false, "EMPTY_BODY", true, 0, null);
            }
            if (root.path("success").isBoolean() && !root.path("success").asBoolean()) {
                String code = DeltaError.codeOf(root, "DELTA_ERROR");
                throw new DeltaApiException("Delta call failed: " + code, false, code, false, 200, null);
            }
            return root;
        } catch (RestClientResponseException e) {
            throw fromResponse(e);
        } catch (RestClientException e) {
            // I/O error or timeout. Only the exception type is reported: its message could echo request details.
            throw new DeltaApiException("Delta I/O error: " + e.getClass().getSimpleName(), false, "IO_ERROR", true, 0, null);
        } catch (JacksonException e) {
            throw badBody();
        } catch (DeltaApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // Only the type is reported: a JDK message could quote a header value.
            throw new DeltaApiException("Delta client error: " + e.getClass().getSimpleName(), false, "CLIENT_ERROR", true, 0, null);
        }
    }

    /**
     * Maps a 2xx body. A malformed field is a retryable BAD_BODY (Delta may well have accepted the request), never a
     * raw parse exception, and the message and cause carry nothing from the body.
     */
    private static <T> T mapped(Supplier<T> mapping) {
        try {
            return mapping.get();
        } catch (RuntimeException e) {
            throw badBody();
        }
    }

    private static void mapped(Runnable mapping) {
        mapped(() -> {
            mapping.run();
            return null;
        });
    }

    private static DeltaApiException badBody() {
        return new DeltaApiException("Delta returned an unreadable body", false, "BAD_BODY", true, 0, null);
    }

    private DeltaApiException fromResponse(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 429) {
            guard.pauseFor(DeltaPublicClient.resetDelay(e.getResponseHeaders()));
            return new DeltaApiException("Delta rate limit hit (HTTP 429)", true, "RATE_LIMITED", false, status, null);
        }
        String code = DeltaError.parse(mapper, e.getResponseBodyAsString(), status).code();
        return new DeltaApiException("Delta call failed: " + code + " (HTTP " + status + ")", false, code, status >= 500, status, null);
    }

    private String json(Object body) {
        return mapper.writeValueAsString(body);
    }

    private static long toMicros(Instant time) {
        return time.getEpochSecond() * 1_000_000 + time.getNano() / 1_000;
    }

    private static DeltaOrder toOrder(JsonNode n) {
        if (!n.path("id").isNumber()) {
            throw new IllegalArgumentException("order id missing");
        }
        return new DeltaOrder(
                n.path("id").asLong(),
                text(n, "client_order_id"),
                text(n, "state"),
                n.path("size").asLong(),
                n.path("unfilled_size").asLong(),
                decimal(n, "average_fill_price"),
                decimal(n, "paid_commission"),
                n.path("product_id").asLong());
    }

    private static DeltaFill toFill(JsonNode n) {
        return new DeltaFill(
                text(n, "id"),
                n.path("order_id").asLong(),
                n.path("size").asLong(),
                decimal(n, "price"),
                decimal(n, "commission"),
                text(n, "role"),
                instant(n.path("created_at")));
    }

    private static String text(JsonNode n, String field) {
        JsonNode value = n.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        String value = text(n, field);
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    /** Delta sends microsecond epoch numbers (sometimes as strings) or ISO-8601 strings. */
    private static Instant instant(JsonNode value) {
        if (value.isNumber() || value.isString() && value.asString().chars().allMatch(Character::isDigit)) {
            long micros = value.asLong();
            return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000);
        }
        return value.isString() ? Instant.parse(value.asString()) : null;
    }
}
