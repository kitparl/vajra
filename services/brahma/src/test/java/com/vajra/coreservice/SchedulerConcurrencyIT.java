package com.vajra.coreservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.vajra.coreservice.connector.Candle;
import com.vajra.coreservice.connector.ExchangeConnector;
import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A slow candle sync (long backfill, slow Delta) must not stop the 2 s quote poller. */
@Testcontainers
@SpringBootTest
class SchedulerConcurrencyIT {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    static final CountDownLatch CANDLE_SYNC_STARTED = new CountDownLatch(1);
    static final CountDownLatch RELEASE_CANDLE_SYNC = new CountDownLatch(1);
    static final AtomicInteger QUOTE_POLLS = new AtomicInteger();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
        registry.add("RISK_API_TOKEN", () -> "test-risk-token");
        registry.add("CORE_INTERNAL_TOKEN", () -> "test-internal-token");
    }

    @AfterAll
    static void release() {
        RELEASE_CANDLE_SYNC.countDown();
    }

    @Test
    void quotePollerKeepsRunningWhileCandleSyncIsSlow() throws Exception {
        assertThat(CANDLE_SYNC_STARTED.await(20, TimeUnit.SECONDS)).as("candle sync started").isTrue();
        int before = QUOTE_POLLS.get();

        Thread.sleep(5000);

        assertThat(QUOTE_POLLS.get() - before).as("quote polls while candle sync was blocked").isGreaterThanOrEqualTo(2);
    }

    @TestConfiguration
    static class SlowCandles {
        @Bean
        @Primary
        ExchangeConnector slowCandleConnector() {
            return new ExchangeConnector() {
                @Override
                public Venue venue() {
                    return Venue.DELTA_TESTNET;
                }

                @Override
                public List<Instrument> instruments(Set<String> enabledSymbols) {
                    return List.of(new Instrument(Venue.DELTA_TESTNET, "BTCUSD", 84, "perpetual_futures",
                            new BigDecimal("0.001"), new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"),
                            new BigDecimal("0.0005"), true, Instant.now()));
                }

                @Override
                public List<Quote> quotes() {
                    QUOTE_POLLS.incrementAndGet();
                    return List.of();
                }

                @Override
                public List<Candle> candles1m(String symbol, Instant from, Instant to) {
                    CANDLE_SYNC_STARTED.countDown();
                    try {
                        RELEASE_CANDLE_SYNC.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of();
                }
            };
        }
    }
}
