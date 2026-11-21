package com.vajra.coreservice.risk;

import com.vajra.coreservice.position.PositionRepository;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** risk-service client: connect and read timeout 500 ms, so a slow or dead risk-service rejects quickly (fail closed). */
@Configuration
class RiskClientConfig {

    static final Duration TIMEOUT = Duration.ofMillis(500);

    @Bean
    RiskServiceGate riskServiceGate(RiskProperties risk, PositionRepository positions) {
        return new RiskServiceGate(timedClient(), risk.baseUrl(), risk.apiToken(), positions);
    }

    static RestClient.Builder timedClient() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(TIMEOUT);
        return RestClient.builder().requestFactory(factory);
    }
}
