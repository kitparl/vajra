package com.vajra.maryada.killswitch;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Calls core-service internal endpoints to cancel open orders and flatten positions. */
public class CoreClient {

    public record KillResult(int cancelledOrders, int flattenOrders, boolean flattened) {}

    private final RestClient http;

    public CoreClient(RestClient.Builder builder, String baseUrl, String token) {
        this.http = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    public KillResult kill(String reason) {
        try {
            KillResult result = http.post().uri("/internal/v1/kill").body(Map.of("reason", reason))
                    .retrieve().body(KillResult.class);
            if (result == null) {
                throw new CoreUnavailableException("core kill returned an empty body", null);
            }
            return result;
        } catch (RestClientException e) {
            throw new CoreUnavailableException("core kill failed", e);
        }
    }

    public void resume() {
        try {
            http.post().uri("/internal/v1/resume").retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new CoreUnavailableException("core resume failed", e);
        }
    }
}
