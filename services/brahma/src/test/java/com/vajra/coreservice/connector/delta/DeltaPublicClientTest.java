package com.vajra.coreservice.connector.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.vajra.coreservice.testsupport.MutableClock;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DeltaPublicClientTest {

    private static final String BASE = DeltaPublicClient.TESTNET_BASE_URL;
    private static final String PRODUCTS = BASE + "/v2/products?contract_types=perpetual_futures&states=live&page_size=100";
    private static final String TICKERS = BASE + "/v2/tickers?contract_types=perpetual_futures";

    private MockRestServiceServer server;
    private MutableClock clock;
    private DeltaPublicClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock(Instant.parse("2026-11-21T10:00:00Z"));
        client = new DeltaPublicClient(builder, new DeltaRateLimitGuard(clock));
    }

    @Test
    void livePerpetualsMapsFieldsAndFollowsPagination() {
        server.expect(requestTo(PRODUCTS))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.USER_AGENT, "vajra"))
                .andRespond(json(fixture("products.json").replace("\"after\": null", "\"after\": \"abc\"")));
        server.expect(requestTo(PRODUCTS + "&after=abc"))
                .andRespond(json("{\"success\":true,\"result\":[" + product("SOLUSD", 3, "\"0.5\"") + "],\"meta\":{\"after\":null}}"));

        List<DeltaProduct> products = client.livePerpetuals();

        server.verify();
        assertThat(products).extracting(DeltaProduct::symbol).containsExactly("ETHUSD", "BTCUSD", "SOLUSD");
        DeltaProduct btc = products.get(1);
        assertThat(btc.id()).isEqualTo(84);
        assertThat(btc.contractType()).isEqualTo("perpetual_futures");
        assertThat(btc.state()).isEqualTo("live");
        assertThat(btc.contractValue()).isEqualByComparingTo("0.001");
        assertThat(btc.tickSize()).isEqualByComparingTo("0.1");
        assertThat(btc.makerCommissionRate()).isEqualByComparingTo("0.0002");
        assertThat(btc.takerCommissionRate()).isEqualByComparingTo("0.0005");
    }

    @Test
    void parsesStringAndNumberDecimals() {
        server.expect(requestTo(PRODUCTS))
                .andRespond(json("{\"success\":true,\"result\":["
                        + product("AAAUSD", 1, "0.5") + "," + product("BBBUSD", 2, "\"0.5\"")
                        + "],\"meta\":{\"after\":null}}"));

        List<DeltaProduct> products = client.livePerpetuals();

        assertThat(products).allSatisfy(p -> assertThat(p.tickSize().compareTo(new BigDecimal("0.5"))).isZero());
    }

    @Test
    void tickerTimestampIsMicroseconds() {
        server.expect(requestTo(TICKERS))
                .andRespond(json(fixture("tickers.json").replace("1795260272421850", "1700000000123456")));

        List<DeltaTicker> tickers = client.perpetualTickers();

        DeltaTicker btc = tickers.stream().filter(t -> t.symbol().equals("BTCUSD")).findFirst().orElseThrow();
        assertThat(btc.timestamp()).isEqualTo(Instant.ofEpochSecond(1700000000, 123456000));
        assertThat(btc.bestBid()).isEqualByComparingTo("57877.5");
        assertThat(btc.bestAsk()).isEqualByComparingTo("57878");
        assertThat(btc.markPrice()).isEqualByComparingTo("57888.60127095");
    }

    @Test
    void malformedTickerIsSkippedNotFatal() {
        server.expect(requestTo(TICKERS))
                .andRespond(json(fixture("tickers.json").replaceFirst("\"timestamp\": \\d+", "\"timestamp\": null")));

        List<DeltaTicker> tickers = client.perpetualTickers();

        assertThat(tickers).hasSize(2);
    }

    @Test
    void jsonNumbersKeepFullPrecision() {
        server.expect(requestTo(BASE + "/v2/history/candles?resolution=1m&symbol=BTCUSD&start=0&end=60"))
                .andRespond(json("{\"success\":true,\"result\":[{\"time\":0,\"open\":57888.60127095,"
                        + "\"high\":0.1000000000000000055,\"low\":1,\"close\":1,\"volume\":1}]}"));

        DeltaCandle candle = client.candles1m("BTCUSD", Instant.EPOCH, Instant.ofEpochSecond(60)).getFirst();

        assertThat(candle.open()).isEqualTo(new BigDecimal("57888.60127095"));
        assertThat(candle.high()).isEqualTo(new BigDecimal("0.1000000000000000055"));
    }

    @Test
    void candlesAreSortedAscendingAndTimeIsSeconds() {
        Instant start = Instant.ofEpochSecond(1795260000);
        Instant end = Instant.ofEpochSecond(1795260300);
        server.expect(requestTo(BASE + "/v2/history/candles?resolution=1m&symbol=BTCUSD&start=1795260000&end=1795260300"))
                .andRespond(json(fixture("candles.json")));

        List<DeltaCandle> candles = client.candles1m("BTCUSD", start, end);

        assertThat(candles).extracting(DeltaCandle::time).containsExactly(
                Instant.ofEpochSecond(1795260120), Instant.ofEpochSecond(1795260180), Instant.ofEpochSecond(1795260240));
        DeltaCandle last = candles.get(2);
        assertThat(last.open()).isEqualByComparingTo("57859.5");
        assertThat(last.high()).isEqualByComparingTo("57877.5");
        assertThat(last.low()).isEqualByComparingTo("57859.5");
        assertThat(last.close()).isEqualByComparingTo("57877.5");
        assertThat(last.volume()).isEqualByComparingTo("1697");
    }

    @Test
    void rateLimitPausesAllCalls() {
        server.expect(requestTo(TICKERS))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("X-RATE-LIMIT-RESET", "30000"));

        assertThatThrownBy(client::perpetualTickers)
                .isInstanceOfSatisfying(DeltaApiException.class, e -> assertThat(e.rateLimited()).isTrue());
        assertThatThrownBy(client::perpetualTickers)
                .isInstanceOfSatisfying(DeltaApiException.class, e -> assertThat(e.rateLimited()).isTrue());
        server.verify();

        server.reset();
        server.expect(requestTo(TICKERS)).andRespond(json(fixture("tickers.json")));
        clock.advance(Duration.ofSeconds(30));

        assertThat(client.perpetualTickers()).isNotEmpty();
        server.verify();
    }

    @Test
    void serverErrorThrowsDeltaApiException() {
        server.expect(requestTo(TICKERS)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(client::perpetualTickers)
                .isInstanceOfSatisfying(DeltaApiException.class, e -> assertThat(e.rateLimited()).isFalse());
    }

    private static org.springframework.test.web.client.ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    private static String product(String symbol, long id, String tickSizeJson) {
        return "{\"id\":" + id + ",\"symbol\":\"" + symbol + "\",\"contract_type\":\"perpetual_futures\",\"state\":\"live\","
                + "\"contract_value\":\"1\",\"tick_size\":" + tickSizeJson + ","
                + "\"maker_commission_rate\":\"0.0002\",\"taker_commission_rate\":\"0.0005\"}";
    }

    static String fixture(String name) {
        try (InputStream in = DeltaPublicClientTest.class.getResourceAsStream("/delta/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
