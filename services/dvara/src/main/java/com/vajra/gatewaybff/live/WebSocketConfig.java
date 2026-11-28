package com.vajra.gatewaybff.live;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(LiveProperties.class)
class WebSocketConfig implements WebSocketConfigurer {

    private final LiveSocketHandler handler;
    private final LiveHandshakeInterceptor interceptor;
    private final LiveProperties properties;

    WebSocketConfig(LiveSocketHandler handler, LiveHandshakeInterceptor interceptor, LiveProperties properties) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                .addInterceptors(interceptor)
                .setAllowedOrigins(properties.uiOrigin());
    }

    /** Logout, idle timeout and the 8 h limit all end by destroying the HTTP session; the sockets follow it. */
    @Bean
    HttpSessionListener liveSessionListener(LiveHub hub) {
        return new HttpSessionListener() {
            @Override
            public void sessionDestroyed(HttpSessionEvent event) {
                Object key = event.getSession().getAttribute(LiveHandshakeInterceptor.LIVE_KEY);
                if (key instanceof String liveKey) {
                    hub.closeForLiveKey(liveKey);
                }
            }
        };
    }
}
