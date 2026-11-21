package com.vajra.coreservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.vajra.coreservice.connector.Candle;
import com.vajra.coreservice.connector.ExchangeConnector;
import com.vajra.coreservice.connector.Venue;
import com.vajra.coreservice.instrument.Instrument;
import com.vajra.coreservice.instrument.InstrumentRepository;
import com.vajra.coreservice.instrument.InstrumentService;
import com.vajra.coreservice.marketdata.CandleRepository;
import com.vajra.coreservice.testsupport.HttpTestClient;
import com.vajra.coreservice.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "vajra.scheduling.enabled=false")
class MarketDataApiIT {

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
    InstrumentRepository instrumentRepository;

    @Autowired
    CandleRepository candleRepository;

    @Value("${local.server.port}")
    int port;

    @BeforeEach
    void loadInstruments() {
        when(connector.venue()).thenReturn(Venue.DELTA_TESTNET);
        when(connector.instruments(any())).thenReturn(List.of(new Instrument(Venue.DELTA_TESTNET, "BTCUSD", 84,
                "perpetual_futures", new BigDecimal("0.001"), new BigDecimal("0.1"), BigDecimal.ONE,
                new BigDecimal("0.0002"), new BigDecimal("0.0005"), true, Instant.parse("2026-11-21T10:00:00Z"))));
        instrumentService.refresh();
    }

    @Test
    void instrumentsListsStoredRows() throws Exception {
        HttpResponse<String> response = HttpTestClient.get(port, "/api/v1/instruments");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"symbol\":\"BTCUSD\"").contains("\"tickSize\":\"0.1\"")
                .contains("\"tradingEnabled\":true");
    }

    @Test
    void candlesReturnsRangeAscending() throws Exception {
        long id = instrumentRepository.idOf(Venue.DELTA_TESTNET, "BTCUSD").orElseThrow();
        candleRepository.upsertAll(id, List.of(candle("10:02"), candle("10:00"), candle("10:01")));

        HttpResponse<String> response = get("BTCUSD", "2026-11-21T10:00:00Z", "2026-11-21T10:02:00Z");

        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat(body).contains("2026-11-21T10:00:00Z").contains("2026-11-21T10:01:00Z").doesNotContain("10:02:00Z");
        assertThat(body.indexOf("10:00:00Z")).isLessThan(body.indexOf("10:01:00Z"));
        assertThat(body).contains("\"close\":\"57000.5\"");
    }

    @Test
    void candlesRejectsBadRange() throws Exception {
        assertBadRequest(get("BTCUSD", "2026-11-21T10:00:00Z", "2026-11-21T10:00:00Z"));
        assertBadRequest(get("BTCUSD", "2026-11-10T10:00:00Z", "2026-11-18T10:00:00Z"));
        assertBadRequest(HttpTestClient.get(port, "/api/v1/candles?symbol=BTCUSD&from=2026-11-21T10:00:00Z"));
        assertBadRequest(get("BTCUSD", "notadate", "2026-11-21T10:00:00Z"));
        assertBadRequest(get("BTCUSD", "%2B300000-01-01T00:00:00Z", "%2B300000-01-02T00:00:00Z"));
    }

    @Test
    void candlesUnknownSymbolIs404() throws Exception {
        HttpResponse<String> response = get("NOPEUSD", "2026-11-21T10:00:00Z", "2026-11-21T10:02:00Z");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"error\"").contains("NOPEUSD");
    }

    private HttpResponse<String> get(String symbol, String from, String to) throws Exception {
        return HttpTestClient.get(port, "/api/v1/candles?symbol=" + symbol + "&from=" + from + "&to=" + to);
    }

    private static void assertBadRequest(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"error\"");
    }

    private static Candle candle(String hhmm) {
        BigDecimal p = new BigDecimal("57000.5");
        return new Candle("BTCUSD", Instant.parse("2026-11-21T" + hhmm + ":00Z"), p, p, p, p, BigDecimal.ONE);
    }
}
