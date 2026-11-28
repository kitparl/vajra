package com.vajra.gatewaybff.live;

import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Security has already required a session; this binds the socket to it (so logout or expiry can close the socket)
 * and requires the {@code Origin} header to equal the configured UI origin exactly. No Origin means no socket.
 */
@Component
class LiveHandshakeInterceptor implements HandshakeInterceptor {

    /** Stable per login session (survives changeSessionId, which does not fire sessionDestroyed). */
    static final String LIVE_KEY = "vajra.liveKey";

    private final LiveProperties properties;

    LiveHandshakeInterceptor(LiveProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Map<String, Object> attributes) {
        String origin = request.getHeaders().getFirst(HttpHeaders.ORIGIN);
        HttpSession session = request instanceof ServletServerHttpRequest servlet
                ? servlet.getServletRequest().getSession(false)
                : null;
        if (origin == null || !origin.equals(properties.uiOrigin()) || session == null) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        Object existing = session.getAttribute(LIVE_KEY);
        String key = existing instanceof String known ? known : UUID.randomUUID().toString();
        session.setAttribute(LIVE_KEY, key);
        attributes.put(LIVE_KEY, key);
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {}
}
