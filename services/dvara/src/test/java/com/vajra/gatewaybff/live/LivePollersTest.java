package com.vajra.gatewaybff.live;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vajra.gatewaybff.upstream.CoreClient;
import com.vajra.gatewaybff.upstream.RiskClient;
import com.vajra.gatewaybff.upstream.UpstreamUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LivePollersTest {

    private static final String CORE_DOWN = "{\"type\":\"upstream\",\"data\":{\"upstream\":\"core\",\"available\":false}}";
    private static final String CORE_UP = "{\"type\":\"upstream\",\"data\":{\"upstream\":\"core\",\"available\":true}}";
    private static final String RISK_DOWN = "{\"type\":\"upstream\",\"data\":{\"upstream\":\"risk\",\"available\":false}}";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final LiveHub hub = mock(LiveHub.class);
    private final CoreClient core = mock(CoreClient.class);
    private final RiskClient risk = mock(RiskClient.class);
    private final MutableClock clock = new MutableClock();
    private final LivePollers pollers = new LivePollers(hub, new LiveMessages(mapper), core, risk,
            new LiveProperties("http://ui.test", Duration.ofSeconds(1), Duration.ofSeconds(2)), clock);

    LivePollersTest() {
        when(hub.count()).thenReturn(1);
    }

    @Test
    void quotePollFailureBroadcastsUpstreamDown() {
        when(core.quotes()).thenThrow(new UpstreamUnavailableException("core", new RuntimeException()));

        for (int i = 0; i < 3; i++) {
            try {
                pollers.pollQuotes();
            } catch (UpstreamUnavailableException expected) {
                // the scheduler's guard logs it
            }
        }

        verify(hub, times(3)).broadcast(CORE_DOWN);
    }

    @Test
    void upstreamUpSentOnceAfterRecovery() throws Exception {
        JsonNode quotes = mapper.readTree("[]");
        when(core.quotes()).thenThrow(new UpstreamUnavailableException("core", new RuntimeException()))
                .thenReturn(quotes);
        try {
            pollers.pollQuotes();
        } catch (UpstreamUnavailableException expected) {
            // logged by the guard
        }

        pollers.pollQuotes();
        pollers.pollQuotes();

        verify(hub, times(1)).broadcast(CORE_UP);
        verify(hub, times(2)).broadcast("{\"type\":\"quote\",\"data\":[]}");
    }

    @Test
    void riskPollFailureBroadcastsUpstreamDownThenUpOnce() throws Exception {
        when(risk.killSwitch()).thenThrow(new UpstreamUnavailableException("risk", new RuntimeException()))
                .thenReturn(mapper.readTree("{\"halted\":false,\"events\":[]}"));
        try {
            pollers.pollRisk();
        } catch (UpstreamUnavailableException expected) {
            // logged by the guard
        }
        verify(hub).broadcast(RISK_DOWN);

        pollers.pollRisk();
        pollers.pollRisk();

        verify(hub, times(1)).broadcast(
                "{\"type\":\"upstream\",\"data\":{\"upstream\":\"risk\",\"available\":true}}");
    }

    @Test
    void riskResentPeriodically() throws Exception {
        when(risk.killSwitch()).thenReturn(mapper.readTree("{\"halted\":false,\"events\":[]}"));
        String json = "{\"type\":\"risk\",\"data\":{\"halted\":false,\"events\":[]}}";

        pollers.pollRisk();
        pollers.pollRisk();
        verify(hub, times(1)).broadcast(json);

        clock.advance(Duration.ofSeconds(9));
        pollers.pollRisk();
        verify(hub, times(1)).broadcast(json);

        clock.advance(Duration.ofSeconds(1));
        pollers.pollRisk();
        verify(hub, times(2)).broadcast(json);
        verify(hub, never()).broadcast(CORE_DOWN);
        verify(hub, never()).sendTo(anyString(), anyString());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-28T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
