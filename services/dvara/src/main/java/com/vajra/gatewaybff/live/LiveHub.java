package com.vajra.gatewaybff.live;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * The open sockets. Every socket is wrapped so concurrent senders (pollers, Kafka thread) are safe and a client that
 * cannot keep up is cut off instead of growing a buffer. A failing socket is closed and dropped on its own.
 */
@Component
class LiveHub {

    /** Application close code: the HTTP session behind the socket ended (logout, idle or absolute timeout). */
    static final CloseStatus SESSION_ENDED = new CloseStatus(4401, "session ended");

    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int BUFFER_SIZE_LIMIT = 256 * 1024;

    private static final Logger log = LoggerFactory.getLogger(LiveHub.class);

    private final Map<String, Socket> sockets = new ConcurrentHashMap<>();

    /** Sends never run on the caller: a socket stuck in a native send must not stall pollers or Kafka. */
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private final int sendTimeLimitMs;
    private final int bufferSizeLimit;

    @Autowired
    LiveHub() {
        this(SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
    }

    LiveHub(int sendTimeLimitMs, int bufferSizeLimit) {
        this.sendTimeLimitMs = sendTimeLimitMs;
        this.bufferSizeLimit = bufferSizeLimit;
    }

    @PreDestroy
    void stop() {
        senders.shutdownNow();
    }

    /** A new socket is pending: bound to its login session (so it can be closed) but skipped by broadcast. */
    void register(WebSocketSession session) {
        sockets.put(session.getId(), new Socket(
                new ConcurrentWebSocketSessionDecorator(session, sendTimeLimitMs, bufferSizeLimit)));
    }

    /** Called once hello and the initial risk state are out; from now on broadcasts reach the socket. */
    void markReady(String socketId) {
        Socket socket = sockets.get(socketId);
        if (socket != null) {
            socket.ready = true;
        }
    }

    boolean isRegistered(String socketId) {
        return sockets.containsKey(socketId);
    }

    void remove(String socketId) {
        sockets.remove(socketId);
    }

    int count() {
        return sockets.size();
    }

    void broadcast(String json) {
        for (Socket socket : sockets.values()) {
            if (socket.ready) {
                send(socket.session, json);
            }
        }
    }

    void sendTo(String socketId, String json) {
        Socket socket = sockets.get(socketId);
        if (socket != null && socket.ready) {
            send(socket.session, json);
        }
    }

    void closeForLiveKey(String liveKey) {
        for (Socket socket : sockets.values()) {
            if (liveKey.equals(socket.session.getAttributes().get(LiveHandshakeInterceptor.LIVE_KEY))) {
                drop(socket.session, SESSION_ENDED);
            }
        }
    }

    /**
     * The decorator queues behind a stuck sender and, once its time or size limit is hit, throws and closes that one
     * socket. Each send runs on its own virtual thread so the stuck sender never holds up the caller.
     */
    private void send(ConcurrentWebSocketSessionDecorator socket, String json) {
        try {
            senders.execute(() -> deliver(socket, json));
        } catch (RuntimeException e) {
            drop(socket, CloseStatus.GOING_AWAY); // executor shut down
        }
    }

    private void deliver(ConcurrentWebSocketSessionDecorator socket, String json) {
        try {
            socket.sendMessage(new TextMessage(json));
        } catch (IOException | RuntimeException e) {
            log.warn("Dropping websocket {}: {}", socket.getId(), e.getClass().getSimpleName());
            drop(socket, CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    private void drop(ConcurrentWebSocketSessionDecorator socket, CloseStatus status) {
        sockets.remove(socket.getId());
        try {
            socket.close(status);
        } catch (IOException | RuntimeException e) {
            log.debug("Websocket {} already closed", socket.getId());
        }
    }

    private static final class Socket {
        final ConcurrentWebSocketSessionDecorator session;
        volatile boolean ready;

        Socket(ConcurrentWebSocketSessionDecorator session) {
            this.session = session;
        }
    }
}
