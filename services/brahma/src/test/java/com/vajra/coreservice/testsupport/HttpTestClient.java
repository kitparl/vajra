package com.vajra.coreservice.testsupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Minimal HTTP GET and POST for integration tests. */
public final class HttpTestClient {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private HttpTestClient() {}

    public static HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> post(int port, String path, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
