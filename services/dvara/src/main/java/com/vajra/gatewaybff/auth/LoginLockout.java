package com.vajra.gatewaybff.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.stereotype.Component;

/**
 * Brute-force guard for the single login: 5 failures within 15 minutes lock logins for 15 minutes. One user, so one counter;
 * a locked attempt does not extend the lock. In memory on purpose: a restart is an operator action anyway.
 */
@Component
public class LoginLockout {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final Clock clock;
    private final Deque<Instant> failures = new ArrayDeque<>();
    private Instant lockedUntil;

    public LoginLockout(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean isLocked() {
        if (lockedUntil == null) {
            return false;
        }
        if (clock.instant().isBefore(lockedUntil)) {
            return true;
        }
        reset();
        return false;
    }

    public synchronized Duration retryAfter() {
        return isLocked() ? Duration.between(clock.instant(), lockedUntil) : Duration.ZERO;
    }

    public synchronized void recordFailure() {
        if (isLocked()) {
            return;
        }
        Instant now = clock.instant();
        failures.removeIf(at -> !at.isAfter(now.minus(LOCK_DURATION)));
        failures.add(now);
        if (failures.size() >= MAX_FAILURES) {
            lockedUntil = now.plus(LOCK_DURATION);
        }
    }

    public synchronized void recordSuccess() {
        reset();
    }

    private void reset() {
        failures.clear();
        lockedUntil = null;
    }
}
