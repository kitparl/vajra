package com.vajra.gatewaybff.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class LiveHubTest {

    private final LiveHub hub = new LiveHub(300, 256 * 1024);

    @AfterEach
    void stop() {
        hub.stop();
    }

    @Test
    @Timeout(10)
    void slowClientDoesNotBlockOthers() throws Exception {
        CountDownLatch stalled = new CountDownLatch(1);
        WebSocketSession slow = socket("slow", "key-1");
        doAnswer(invocation -> {
            stalled.await();
            return null;
        }).when(slow).sendMessage(any(TextMessage.class));
        WebSocketSession fast = socket("fast", "key-2");
        add(slow);
        add(fast);

        long start = System.nanoTime();
        hub.broadcast("one");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(200));
        verify(fast, timeout(1000)).sendMessage(new TextMessage("one"));

        Thread.sleep(400); // past the (test) 300 ms send time limit of the stalled socket
        hub.broadcast("two");

        verify(slow, timeout(2000)).close(any(CloseStatus.class));
        verify(fast, timeout(1000)).sendMessage(new TextMessage("two"));
        verify(fast, never()).close(any(CloseStatus.class));
        assertThat(hub.count()).isEqualTo(1);
        stalled.countDown();
    }

    @Test
    void closeForLiveKeyClosesOnlyBoundSockets() throws Exception {
        WebSocketSession a = socket("a", "key-1");
        WebSocketSession b = socket("b", "key-1");
        WebSocketSession c = socket("c", "key-2");
        add(a);
        add(b);
        add(c);

        hub.closeForLiveKey("key-1");

        CloseStatus expected = new CloseStatus(4401, "session ended");
        verify(a).close(expected);
        verify(b).close(expected);
        verify(c, never()).close(any(CloseStatus.class));
        assertThat(hub.count()).isEqualTo(1);
    }

    @Test
    void pendingSocketSkippedByBroadcastUntilReadyAndClosedOnSessionEnd() throws Exception {
        WebSocketSession pending = socket("pending", "key-1");
        hub.register(pending);

        hub.broadcast("early");
        Thread.sleep(200);
        verify(pending, never()).sendMessage(any(TextMessage.class));

        hub.closeForLiveKey("key-1");
        verify(pending).close(new CloseStatus(4401, "session ended"));
        assertThat(hub.isRegistered("pending")).isFalse();
    }

    @Test
    void readySocketReceivesBroadcast() throws Exception {
        WebSocketSession socket = socket("s", "key-1");
        hub.register(socket);
        hub.markReady("s");

        hub.broadcast("late");

        verify(socket, timeout(1000)).sendMessage(new TextMessage("late"));
    }

    private void add(WebSocketSession session) {
        hub.register(session);
        hub.markReady(session.getId());
    }

    private static WebSocketSession socket(String id, String httpSessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(LiveHandshakeInterceptor.LIVE_KEY, httpSessionId));
        return session;
    }
}
