package com.vajra.gatewaybff.live;

import com.vajra.gatewaybff.upstream.CoreClient;
import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Server-to-client only: incoming frames are ignored. A new socket gets a hello, then the current risk state. */
@Component
class LiveSocketHandler extends TextWebSocketHandler {

    private final LiveHub hub;
    private final LiveMessages messages;
    private final LivePollers pollers;
    private final CoreClient core;

    LiveSocketHandler(LiveHub hub, LiveMessages messages, LivePollers pollers, CoreClient core) {
        this.hub = hub;
        this.messages = messages;
        this.pollers = pollers;
        this.core = core;
    }

    /**
     * Registered at once (pending) so a logout during the slow upstream calls still closes it. Hello and the current
     * risk state go out on the raw socket, nothing else can reach it until it is marked ready.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String id = session.getId();
        hub.register(session);
        String hello = messages.hello(core.tradingMode());
        if (!sendWhileRegistered(session, hello)) {
            return;
        }
        Optional<String> risk = pollers.riskForNewSocket();
        if (risk.isPresent() && !sendWhileRegistered(session, risk.get())) {
            return;
        }
        hub.markReady(id);
        risk.ifPresent(sent -> pollers.catchUpRisk(id, sent));
    }

    /** False when the socket was closed meanwhile (session ended) or cannot be written to; then it is dropped. */
    private boolean sendWhileRegistered(WebSocketSession session, String json) {
        if (!hub.isRegistered(session.getId())) {
            return false;
        }
        try {
            session.sendMessage(new TextMessage(json));
            return true;
        } catch (IOException | RuntimeException e) {
            hub.remove(session.getId());
            return false;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        hub.remove(session.getId());
    }
}
