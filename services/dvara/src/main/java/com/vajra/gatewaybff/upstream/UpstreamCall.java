package com.vajra.gatewaybff.upstream;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** One read-only upstream: GET with timeouts, 4xx passed through, everything else unavailable. */
final class UpstreamCall {

    /** Connect and read are separate budgets, so one call is at most about 1 s + timeout (4 s) for the UI's 5 s poll. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(UpstreamCall.class);

    private final String name;
    private final RestClient client;

    UpstreamCall(String name, String baseUrl, Duration timeout, Consumer<HttpHeaders> defaultHeaders) {
        this.name = name;
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .connectTimeout(timeout.compareTo(CONNECT_TIMEOUT) < 0 ? timeout : CONNECT_TIMEOUT)
                        .build());
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeaders(defaultHeaders)
                .build();
    }

    /** {@code pathTemplate} may use {@code {var}} placeholders; each {@code query} entry is sent as a query param. */
    JsonNode get(String pathTemplate, Map<String, String> pathVariables, Map<String, String> query) {
        String queryTemplate = query.keySet().stream().map(key -> key + "={" + key + "}").collect(Collectors.joining("&"));
        Map<String, String> variables = new HashMap<>(pathVariables);
        variables.putAll(query);
        try {
            JsonNode body = client.get()
                    .uri(queryTemplate.isEmpty() ? pathTemplate : pathTemplate + "?" + queryTemplate, variables)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(), (request, response) -> {
                        int code = response.getStatusCode().value();
                        if (code == 401 || code == 403) {
                            // our credentials to the upstream are wrong; the UI must not read this as "not logged in"
                            log.warn("Upstream {} refused the gateway with {}", name, code);
                            throw new UpstreamUnavailableException(name, null);
                        }
                        throw new UpstreamRejectedException(
                                response.getStatusCode(),
                                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                    })
                    .onStatus(status -> status.is5xxServerError(), (request, response) -> {
                        throw new UpstreamUnavailableException(name, null);
                    })
                    .body(JsonNode.class);
            if (body == null || body.isNull()) {
                throw new UpstreamUnavailableException(name, null);
            }
            return body;
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException(name, e);
        }
    }
}
