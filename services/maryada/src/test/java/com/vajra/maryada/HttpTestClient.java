package com.vajra.maryada;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class HttpTestClient {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private HttpTestClient() {}

    public static HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> getWithToken(int port, String path, String bearerToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearerToken)
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** POST a JSON body; {@code bearerToken} may be null (no Authorization header). */
    public static HttpResponse<String> postJson(int port, String path, String json, String bearerToken) throws Exception {
        return postJsonWithAuthorization(port, path, json, bearerToken == null ? null : "Bearer " + bearerToken);
    }

    /** POST a JSON body with a raw Authorization header value (null = none). The path is sent as given. */
    public static HttpResponse<String> postJsonWithAuthorization(int port, String path, String json, String authorization)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
