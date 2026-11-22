package com.vajra.coreservice.connector.delta;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Delta client beans with the timeouts from the plan (connect 3 s, read 5 s). */
@Configuration
class DeltaConfig {

    @Bean
    DeltaRateLimitGuard deltaRateLimitGuard(Clock clock) {
        return new DeltaRateLimitGuard(clock);
    }

    @Bean
    DeltaPublicClient deltaPublicClient(DeltaRateLimitGuard guard) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new DeltaPublicClient(RestClient.builder().requestFactory(factory), guard);
    }

    @Bean
    DeltaCredentials deltaCredentials(
            @Value("${vajra.delta.api-key:}") String apiKey, @Value("${vajra.delta.api-secret:}") String apiSecret) {
        return new DeltaCredentials(apiKey, apiSecret);
    }

    @Bean
    DeltaPrivateClient deltaPrivateClient(DeltaRateLimitGuard guard, DeltaCredentials credentials, Clock clock) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new DeltaPrivateClient(RestClient.builder().requestFactory(factory), guard, credentials, clock);
    }
}
