package com.vajra.gatewaybff.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginLockoutTest {

    private static final Instant START = Instant.parse("2026-11-28T10:00:00Z");

    private Instant now = START;
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
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
    };
    private final LoginLockout lockout = new LoginLockout(clock);

    @Test
    void lockoutLiftsAfter15MinutesAndSuccessResets() {
        for (int i = 0; i < 4; i++) {
            lockout.recordFailure();
        }
        assertThat(lockout.isLocked()).isFalse();
        lockout.recordFailure();
        assertThat(lockout.isLocked()).isTrue();
        assertThat(lockout.retryAfter()).isEqualTo(Duration.ofMinutes(15));

        now = START.plus(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(lockout.isLocked()).isTrue();
        assertThat(lockout.retryAfter()).isEqualTo(Duration.ofSeconds(1));

        now = START.plus(Duration.ofMinutes(15));
        assertThat(lockout.isLocked()).isFalse();
        assertThat(lockout.retryAfter()).isEqualTo(Duration.ZERO);

        // failure count restarts after the lock lifts: 4 more failures do not lock again
        for (int i = 0; i < 4; i++) {
            lockout.recordFailure();
        }
        assertThat(lockout.isLocked()).isFalse();
    }

    @Test
    void successResetsFailureCount() {
        for (int i = 0; i < 4; i++) {
            lockout.recordFailure();
        }
        lockout.recordSuccess();
        for (int i = 0; i < 4; i++) {
            lockout.recordFailure();
        }
        assertThat(lockout.isLocked()).isFalse();
    }

    @Test
    void failuresOlderThan15MinDoNotCount() {
        for (int i = 0; i < 4; i++) {
            lockout.recordFailure();
        }
        now = START.plus(Duration.ofMinutes(16));

        lockout.recordFailure();

        assertThat(lockout.isLocked()).isFalse();
    }

    @Test
    void attemptWhileLockedDoesNotExtendLock() {
        for (int i = 0; i < 5; i++) {
            lockout.recordFailure();
        }
        now = START.plus(Duration.ofMinutes(10));

        lockout.recordFailure();

        assertThat(lockout.retryAfter()).isEqualTo(Duration.ofMinutes(5));
    }
}
