package com.vajra.gatewaybff.live;

import com.vajra.gatewaybff.upstream.CoreClient;
import com.vajra.gatewaybff.upstream.RiskClient;
import com.vajra.gatewaybff.upstream.UpstreamUnavailableException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Quotes and risk state are polled from core and risk, but only while someone is listening. A failed poll is logged
 * (at most once a minute per poller) and the next tick tries again. While core or risk is unreachable every failed
 * poll broadcasts {@code upstream available:false}, so the terminal keeps hearing from the gateway; the first
 * success afterwards broadcasts {@code available:true} once. Risk goes out when it changed, again every
 * {@link #RISK_RESEND_EVERY} (a lost or reordered message must not stick), and once to each new socket.
 */
@Component
class LivePollers {

    private static final Logger log = LoggerFactory.getLogger(LivePollers.class);
    private static final Duration WARN_EVERY = Duration.ofMinutes(1);

    private static final Duration RISK_RESEND_EVERY = Duration.ofSeconds(10);

    private final LiveHub hub;
    private final LiveMessages messages;
    private final CoreClient core;
    private final RiskClient risk;
    private final LiveProperties properties;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final Clock clock;
    private volatile String lastRisk;
    private volatile Instant lastRiskSentAt = Instant.MIN;
    private volatile boolean coreDown;
    private volatile boolean riskDown;
    private final WarnThrottle quoteWarn;
    private final WarnThrottle riskWarn;

    LivePollers(LiveHub hub, LiveMessages messages, CoreClient core, RiskClient risk, LiveProperties properties,
            Clock clock) {
        this.hub = hub;
        this.messages = messages;
        this.core = core;
        this.risk = risk;
        this.properties = properties;
        this.clock = clock;
        this.quoteWarn = new WarnThrottle(clock, WARN_EVERY);
        this.riskWarn = new WarnThrottle(clock, WARN_EVERY);
    }

    @PostConstruct
    void start() {
        executor.scheduleWithFixedDelay(() -> guarded("quotes", quoteWarn, this::pollQuotes), 0,
                properties.quoteInterval().toMillis(), TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(() -> guarded("risk", riskWarn, this::pollRisk), 0,
                properties.riskInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /**
     * The current risk state for a socket that is still connecting (empty if risk is unreachable). If it differs from
     * what the others last saw it is broadcast to them too.
     */
    Optional<String> riskForNewSocket() {
        try {
            String json = riskJson();
            if (!json.equals(lastRisk)) {
                lastRisk = json;
                lastRiskSentAt = clock.instant();
                hub.broadcast(json);
            }
            return Optional.of(json);
        } catch (RuntimeException e) {
            warn("risk", riskWarn, e);
            return Optional.empty();
        }
    }

    /**
     * After a socket turns ready: if risk changed while it was pending (broadcast skipped it), send it the newer
     * state. A duplicate of what it already has is harmless; a missed change is not.
     */
    void catchUpRisk(String socketId, String sentJson) {
        String current = lastRisk;
        if (current != null && !current.equals(sentJson)) {
            hub.sendTo(socketId, current);
        }
    }

    void pollQuotes() {
        if (hub.count() == 0) {
            return;
        }
        JsonNode quotes;
        try {
            quotes = core.quotes();
        } catch (UpstreamUnavailableException e) {
            coreDown = true;
            hub.broadcast(messages.upstream("core", false));
            throw e;
        }
        if (coreDown) {
            coreDown = false;
            hub.broadcast(messages.upstream("core", true));
        }
        hub.broadcast(messages.quotes(quotes));
    }

    void pollRisk() {
        if (hub.count() == 0) {
            return;
        }
        String json;
        try {
            json = riskJson();
        } catch (UpstreamUnavailableException e) {
            riskDown = true;
            hub.broadcast(messages.upstream("risk", false));
            throw e;
        }
        if (riskDown) {
            riskDown = false;
            hub.broadcast(messages.upstream("risk", true));
        }
        Instant now = clock.instant();
        if (!json.equals(lastRisk) || !now.isBefore(lastRiskSentAt.plus(RISK_RESEND_EVERY))) {
            lastRisk = json;
            lastRiskSentAt = now;
            hub.broadcast(json);
        }
    }

    private String riskJson() {
        JsonNode status = risk.killSwitch();
        return messages.risk(status);
    }

    private void guarded(String name, WarnThrottle throttle, Runnable poll) {
        try {
            poll.run();
        } catch (RuntimeException e) {
            warn(name, throttle, e);
        }
    }

    private void warn(String name, WarnThrottle throttle, RuntimeException e) {
        if (throttle.tryAcquire()) {
            log.warn("Live {} poll failed: {}", name, e.getMessage());
        }
    }
}
