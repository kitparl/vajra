package com.vajra.gatewaybff.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WarnThrottleTest {

    @Test
    void allowsOncePerWindowAndEachThrottleIsIndependent() {
        long[] now = {0};
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now[0]); }
        };
        WarnThrottle quotes = new WarnThrottle(clock, Duration.ofMinutes(1));
        WarnThrottle risk = new WarnThrottle(clock, Duration.ofMinutes(1));

        assertThat(quotes.tryAcquire()).isTrue();
        assertThat(quotes.tryAcquire()).isFalse();
        assertThat(risk.tryAcquire()).isTrue();
        now[0] = 61_000;
        assertThat(quotes.tryAcquire()).isTrue();
    }
}
