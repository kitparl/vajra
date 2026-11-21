package com.vajra.brahma.testsupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Minimal HTTP GET for integration tests. */
public final class HttpTestClient {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private HttpTestClient() {}

    public static HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
