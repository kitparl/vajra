package com.vajra.coreservice.connector.delta;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Public (no API key) Delta Exchange India REST calls. Testnet only: there is no production URL in Phase 2. */
public class DeltaPublicClient {

    public static final String TESTNET_BASE_URL = "https://cdn-ind.testnet.deltaex.org";
    static final Duration DEFAULT_RATE_LIMIT_PAUSE = Duration.ofSeconds(60);
    private static final int PAGE_SIZE = 100;

    private final RestClient rest;
    private final DeltaRateLimitGuard guard;

    public DeltaPublicClient(RestClient.Builder builder, DeltaRateLimitGuard guard) {
        this.rest = builder.baseUrl(TESTNET_BASE_URL).defaultHeader(HttpHeaders.USER_AGENT, "vajra").build();
        this.guard = guard;
    }

    public List<DeltaProduct> livePerpetuals() {
        List<DeltaProduct> products = new ArrayList<>();
        String after = null;
        do {
            String cursor = after;
            Envelope<List<DeltaProduct>> page = call(() -> rest.get()
                    .uri(uri -> {
                        uri.path("/v2/products")
                                .queryParam("contract_types", "perpetual_futures")
                                .queryParam("states", "live")
                                .queryParam("page_size", PAGE_SIZE);
                        if (cursor != null) {
                            uri.queryParam("after", cursor);
                        }
                        return uri.build();
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<Envelope<List<DeltaProduct>>>() {}));
            products.addAll(page.result());
            after = page.meta() == null ? null : page.meta().after();
        } while (after != null);
        return products;
    }

    public List<DeltaTicker> perpetualTickers() {
        Envelope<List<RawTicker>> response = call(() -> rest.get()
                .uri(uri -> uri.path("/v2/tickers").queryParam("contract_types", "perpetual_futures").build())
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope<List<RawTicker>>>() {}));
        return response.result().stream()
                .filter(RawTicker::isComplete)
                .map(RawTicker::toTicker)
                .toList();
    }

    public List<DeltaCandle> candles1m(String symbol, Instant start, Instant end) {
        Envelope<List<RawCandle>> response = call(() -> rest.get()
                .uri(uri -> uri.path("/v2/history/candles")
                        .queryParam("resolution", "1m")
                        .queryParam("symbol", symbol)
                        .queryParam("start", start.getEpochSecond())
                        .queryParam("end", end.getEpochSecond())
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope<List<RawCandle>>>() {}));
        return response.result().stream()
                .map(RawCandle::toCandle)
                .sorted(Comparator.comparing(DeltaCandle::time))
                .toList();
    }

    private <T> Envelope<T> call(Supplier<Envelope<T>> request) {
        guard.checkAllowed();
        try {
            Envelope<T> envelope = request.get();
            if (envelope == null || envelope.result() == null) {
                throw new DeltaApiException("Delta returned an empty body", false, null);
            }
            return envelope;
        } catch (HttpClientErrorException.TooManyRequests e) {
            guard.pauseFor(resetDelay(e.getResponseHeaders()));
            throw new DeltaApiException("Delta rate limit hit (HTTP 429)", true, e);
        } catch (RestClientException e) {
            throw new DeltaApiException("Delta call failed: " + e.getMessage(), false, e);
        }
    }

    static Duration resetDelay(HttpHeaders headers) {
        String reset = headers == null ? null : headers.getFirst("X-RATE-LIMIT-RESET");
        if (reset == null) {
            return DEFAULT_RATE_LIMIT_PAUSE;
        }
        try {
            return Duration.ofMillis(Long.parseLong(reset.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_RATE_LIMIT_PAUSE;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Envelope<T>(@JsonProperty("result") T result, @JsonProperty("meta") Meta meta) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Meta(@JsonProperty("after") String after) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Quotes(@JsonProperty("best_bid") BigDecimal bestBid, @JsonProperty("best_ask") BigDecimal bestAsk) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawTicker(
            @JsonProperty("symbol") String symbol,
            @JsonProperty("mark_price") BigDecimal markPrice,
            @JsonProperty("timestamp") Long timestampMicros,
            @JsonProperty("quotes") Quotes quotes) {

        /** One malformed ticker among all perpetuals must not drop the whole poll. */
        boolean isComplete() {
            return symbol != null && timestampMicros != null;
        }

        DeltaTicker toTicker() {
            BigDecimal bid = quotes == null ? null : quotes.bestBid();
            BigDecimal ask = quotes == null ? null : quotes.bestAsk();
            Instant at = Instant.ofEpochSecond(timestampMicros / 1_000_000, (timestampMicros % 1_000_000) * 1_000);
            return new DeltaTicker(symbol, bid, ask, markPrice, at);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawCandle(
            @JsonProperty("time") long time,
            @JsonProperty("open") BigDecimal open,
            @JsonProperty("high") BigDecimal high,
            @JsonProperty("low") BigDecimal low,
            @JsonProperty("close") BigDecimal close,
            @JsonProperty("volume") BigDecimal volume) {

        DeltaCandle toCandle() {
            return new DeltaCandle(Instant.ofEpochSecond(time), open, high, low, close, volume);
        }
    }
}
