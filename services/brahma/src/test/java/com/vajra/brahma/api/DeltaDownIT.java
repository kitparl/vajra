package com.vajra.brahma.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.vajra.brahma.connector.Candle;
import com.vajra.brahma.connector.ExchangeConnector;
import com.vajra.brahma.connector.Quote;
import com.vajra.brahma.connector.Venue;
import com.vajra.brahma.connector.delta.DeltaApiException;
import com.vajra.brahma.instrument.Instrument;
import com.vajra.brahma.testsupport.HttpTestClient;
import com.vajra.brahma.testsupport.TestDatabase;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Scheduling ON, every venue call fails: the service must still start and report UP. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeltaDownIT {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    static final CountDownLatch QUOTE_POLLS = new CountDownLatch(2);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void startsAndIsHealthyWhenDeltaIsDown() throws Exception {
        assertThat(QUOTE_POLLS.await(10, TimeUnit.SECONDS)).as("quote poller kept running after failures").isTrue();

        HttpResponse<String> response = HttpTestClient.get(port, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @TestConfiguration
    static class FailingVenue {
        @Bean
        @Primary
        ExchangeConnector failingConnector() {
            return new ExchangeConnector() {
                @Override
                public Venue venue() {
                    return Venue.DELTA_TESTNET;
                }

                @Override
                public List<Instrument> instruments(Set<String> enabledSymbols) {
                    throw new DeltaApiException("down", false, null);
                }

                @Override
                public List<Quote> quotes() {
                    QUOTE_POLLS.countDown();
                    throw new DeltaApiException("down", false, null);
                }

                @Override
                public List<Candle> candles1m(String symbol, Instant from, Instant to) {
                    throw new DeltaApiException("down", false, null);
                }
            };
        }
    }
}
