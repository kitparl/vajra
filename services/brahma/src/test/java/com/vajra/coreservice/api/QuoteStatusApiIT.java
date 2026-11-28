package com.vajra.coreservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.vajra.coreservice.connector.ExchangeConnector;
import com.vajra.coreservice.connector.Quote;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentService;
import com.vajra.coreservice.marketdata.QuoteCache;
import com.vajra.coreservice.testsupport.HttpTestClient;
import com.vajra.coreservice.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "vajra.scheduling.enabled=false")
class QuoteStatusApiIT {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.registerAsServiceRole(POSTGRES, registry);
        registry.add("RISK_API_TOKEN", () -> "test-risk-token");
        registry.add("CORE_INTERNAL_TOKEN", () -> "test-internal-token");
    }

    @MockitoBean
    ExchangeConnector connector;

    @Autowired
    InstrumentService instrumentService;

    @Autowired
    QuoteCache quoteCache;

    @Value("${local.server.port}")
    int port;

    @BeforeEach
    void seed() {
        when(connector.venue()).thenReturn(Venue.DELTA_TESTNET);
        when(connector.instruments(any())).thenReturn(List.of(instrument("BTCUSD", 84), instrument("ETHUSD", 85)));
        instrumentService.refresh();
        Instant now = Instant.now();
        quoteCache.put(quote("ETHUSD", now.minusSeconds(60)));
        quoteCache.put(quote("BTCUSD", now));
    }

    @Test
    void quotesJsonContract() throws Exception {
        HttpResponse<String> response = HttpTestClient.get(port, "/api/v1/quotes");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JsonMapper.builder().build().readTree(response.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(2);
        JsonNode btc = body.get(0);
        assertThat(btc.get("symbol").asString()).isEqualTo("BTCUSD");
        assertThat(btc.propertyNames()).containsExactlyInAnyOrder("symbol", "bestBid", "bestAsk", "markPrice", "venueTime",
                "receivedAt", "stale");
        assertThat(btc.get("bestBid").isString()).isTrue();
        assertThat(btc.get("bestBid").asString()).isEqualTo("100.0");
        assertThat(btc.get("markPrice").isString()).isTrue();
        assertThat(Instant.parse(btc.get("receivedAt").asString())).isNotNull();
        assertThat(btc.get("stale").isBoolean()).isTrue();
        assertThat(btc.get("stale").asBoolean()).isFalse();
        assertThat(body.get(1).get("symbol").asString()).isEqualTo("ETHUSD");
        assertThat(body.get(1).get("stale").asBoolean()).isTrue();
    }

    @Test
    void statusReturnsConfiguredMode() throws Exception {
        HttpResponse<String> response = HttpTestClient.get(port, "/api/v1/status");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JsonMapper.builder().build().readTree(response.body());
        assertThat(new ArrayList<>(body.propertyNames())).containsExactly("tradingMode");
        assertThat(body.get("tradingMode").asString()).isEqualTo("PAPER");
    }

    private static Instrument instrument(String symbol, long productId) {
        return new Instrument(Venue.DELTA_TESTNET, symbol, productId, "perpetual_futures", new BigDecimal("0.001"),
                new BigDecimal("0.1"), BigDecimal.ONE, new BigDecimal("0.0002"), new BigDecimal("0.0005"), true,
                Instant.parse("2026-11-21T10:00:00Z"));
    }

    private static Quote quote(String symbol, Instant receivedAt) {
        return new Quote(symbol, new BigDecimal("100.0"), new BigDecimal("100.5"), new BigDecimal("100.2"), receivedAt, receivedAt);
    }
}
