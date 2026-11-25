package com.saanscheck.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-account brute-force protection: after {@code max-failures} failed logins within {@code window},
 * further attempts for that email are refused (HTTP 429) until old failures age out.
 *
 * <p>In-memory, so it is per backend instance and resets on restart; enough for a single-instance
 * deployment. Keyed by email rather than IP because the app has no trusted proxy configuration.
 */
@Component
public class LoginAttemptLimiter {

    private static final int MAX_TRACKED_ACCOUNTS = 100_000;

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginAttemptLimiter(@Value("${saanscheck.security.login.max-failures:5}") int maxFailures,
                               @Value("${saanscheck.security.login.window:PT15M}") Duration window,
                               Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    public boolean isBlocked(String key) {
        Deque<Instant> q = failures.get(key);
        if (q == null) {
            return false;
        }
        synchronized (q) {
            prune(q);
            return q.size() >= maxFailures;
        }
    }

    public void recordFailure(String key) {
        if (failures.size() > MAX_TRACKED_ACCOUNTS) {
            failures.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    prune(e.getValue());
                    return e.getValue().isEmpty();
                }
            });
        }
        Deque<Instant> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            q.addLast(clock.instant());
        }
    }

    public void reset(String key) {
        failures.remove(key);
    }

    private void prune(Deque<Instant> q) {
        Instant cutoff = clock.instant().minus(window);
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
            q.pollFirst();
        }
    }

    public static class TooManyAttemptsException extends RuntimeException {
        public TooManyAttemptsException() {
            super("too many failed login attempts");
        }
    }
}
