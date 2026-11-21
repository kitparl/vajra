package com.vajra.brahma.connector.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.vajra.brahma.connector.Candle;
import com.vajra.brahma.connector.Quote;
import com.vajra.brahma.connector.Venue;
import com.vajra.brahma.instrument.Instrument;
import com.vajra.brahma.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DeltaConnectorTest {

    private static final String BASE = DeltaPublicClient.TESTNET_BASE_URL;
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    private MockRestServiceServer server;
    private DeltaConnector connector;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        MutableClock clock = new MutableClock(NOW);
        connector = new DeltaConnector(new DeltaPublicClient(builder, new DeltaRateLimitGuard(clock)), clock);
    }

    @Test
    void mapsProductToInstrument() {
        server.expect(requestTo(BASE + "/v2/products?contract_types=perpetual_futures&states=live&page_size=100"))
                .andRespond(withSuccess(DeltaPublicClientTest.fixture("products.json"), MediaType.APPLICATION_JSON));

        List<Instrument> instruments = connector.instruments(Set.of("BTCUSD"));

        Instrument btc = instruments.stream().filter(i -> i.symbol().equals("BTCUSD")).findFirst().orElseThrow();
        assertThat(btc.venue()).isEqualTo(Venue.DELTA_TESTNET);
        assertThat(btc.venueProductId()).isEqualTo(84);
        assertThat(btc.contractType()).isEqualTo("perpetual_futures");
        assertThat(btc.contractValue()).isEqualByComparingTo("0.001");
        assertThat(btc.tickSize()).isEqualByComparingTo("0.1");
        assertThat(btc.minSize()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(btc.makerFee()).isEqualByComparingTo("0.0002");
        assertThat(btc.takerFee()).isEqualByComparingTo("0.0005");
        assertThat(btc.tradingEnabled()).isTrue();
        assertThat(btc.updatedAt()).isEqualTo(NOW);
        Instrument eth = instruments.stream().filter(i -> i.symbol().equals("ETHUSD")).findFirst().orElseThrow();
        assertThat(eth.tradingEnabled()).isFalse();
    }

    @Test
    void mapsTickersToQuotesWithReceiveTime() {
        server.expect(requestTo(BASE + "/v2/tickers?contract_types=perpetual_futures"))
                .andRespond(withSuccess(DeltaPublicClientTest.fixture("tickers.json"), MediaType.APPLICATION_JSON));

        List<Quote> quotes = connector.quotes();

        Quote btc = quotes.stream().filter(q -> q.symbol().equals("BTCUSD")).findFirst().orElseThrow();
        assertThat(btc.bestBid()).isEqualByComparingTo("57877.5");
        assertThat(btc.bestAsk()).isEqualByComparingTo("57878");
        assertThat(btc.receivedAt()).isEqualTo(NOW);
        assertThat(btc.venueTime()).isEqualTo(Instant.ofEpochSecond(1795260272, 421850000));
    }

    @Test
    void mapsCandlesWithSymbol() {
        Instant from = Instant.ofEpochSecond(1795260000);
        Instant to = Instant.ofEpochSecond(1795260300);
        server.expect(requestTo(BASE + "/v2/history/candles?resolution=1m&symbol=BTCUSD&start=1795260000&end=1795260300"))
                .andRespond(withSuccess(DeltaPublicClientTest.fixture("candles.json"), MediaType.APPLICATION_JSON));

        List<Candle> candles = connector.candles1m("BTCUSD", from, to);

        assertThat(candles).hasSize(3).allSatisfy(c -> assertThat(c.symbol()).isEqualTo("BTCUSD"));
        assertThat(candles.getFirst().openTime()).isEqualTo(Instant.ofEpochSecond(1795260120));
        assertThat(candles.getFirst().close()).isEqualByComparingTo("57870");
    }
}
