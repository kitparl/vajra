package com.vajra.brahma.risk;

import com.vajra.brahma.position.PositionRepository;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** risk-service client: connect and read timeout 500 ms, so a slow or dead maryada rejects quickly (fail closed). */
@Configuration
class RiskClientConfig {

    static final Duration TIMEOUT = Duration.ofMillis(500);

    @Bean
    MaryadaRiskGate maryadaRiskGate(RiskProperties risk, PositionRepository positions) {
        return new MaryadaRiskGate(timedClient(), risk.baseUrl(), risk.apiToken(), positions);
    }

    static RestClient.Builder timedClient() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(TIMEOUT);
        return RestClient.builder().requestFactory(factory);
    }
}
