package com.vajra.brahma;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Own container, because this test stops it. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrahmaDatabaseDownIT {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void healthIsDownWhenDatabaseStops() throws Exception {
        POSTGRES.stop();

        HttpResponse<String> response = HttpTestClient.get(port, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"status\":\"DOWN\"");
    }
}
