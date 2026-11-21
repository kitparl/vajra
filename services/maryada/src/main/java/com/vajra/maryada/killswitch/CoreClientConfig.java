package com.vajra.maryada.killswitch;

import com.vajra.maryada.api.SecurityProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** core-service client with connect 1 s / read 10 s timeouts. */
@Configuration
class CoreClientConfig {

    @Bean
    CoreClient coreClient(SecurityProperties security, @Value("${vajra.core.base-url}") String baseUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new CoreClient(RestClient.builder().requestFactory(factory), baseUrl, security.coreInternalToken());
    }
}
