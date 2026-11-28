package com.vajra.gatewaybff.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vajra.gatewaybff.upstream.CoreClient;
import com.vajra.gatewaybff.upstream.RiskClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class LiveSocketHandlerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final LiveHub hub = new LiveHub();

    @AfterEach
    void stop() {
        hub.stop();
    }

    @Test
    void riskChangeDuringPendingIsDeliveredAfterReady() throws Exception {
        CoreClient core = mock(CoreClient.class);
        when(core.tradingMode()).thenReturn("PAPER");
        RiskClient risk = mock(RiskClient.class);
        when(risk.killSwitch()).thenReturn(mapper.readTree("{\"halted\":false,\"events\":[]}"));
        LiveMessages messages = new LiveMessages(mapper);
        LivePollers pollers = new LivePollers(hub, messages, core, risk,
                new LiveProperties("http://ui.test", Duration.ofSeconds(1), Duration.ofSeconds(2)), Clock.systemUTC());
        LiveSocketHandler handler = new LiveSocketHandler(hub, messages, pollers, core);

        List<String> sent = new CopyOnWriteArrayList<>();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(LiveHandshakeInterceptor.LIVE_KEY, "k"));
        doAnswer(invocation -> {
            String payload = ((TextMessage) invocation.getArgument(0)).getPayload();
            sent.add(payload);
            if (payload.contains("\"type\":\"risk\"") && payload.contains("\"halted\":false")) {
                // while this socket is still pending, the poller sees the kill switch trip
                when(risk.killSwitch()).thenReturn(mapper.readTree("{\"halted\":true,\"events\":[]}"));
                pollers.pollRisk();
            }
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        handler.afterConnectionEstablished(session);

        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!lastRisk(sent).contains("\"halted\":true") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(lastRisk(sent)).contains("\"halted\":true");
    }

    private static String lastRisk(List<String> sent) {
        return sent.stream().filter(m -> m.contains("\"type\":\"risk\"")).reduce((a, b) -> b).orElse("");
    }
}
