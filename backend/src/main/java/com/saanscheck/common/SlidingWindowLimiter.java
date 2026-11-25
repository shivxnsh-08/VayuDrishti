package com.saanscheck.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory "at most N events per key within a sliding window" limiter (single backend instance).
 * Used for Telegram link-code generation, failed link attempts and test messages.
 */
public class SlidingWindowLimiter {

    private static final int MAX_KEYS = 100_000;

    private final int max;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> events = new ConcurrentHashMap<>();

    public SlidingWindowLimiter(int max, Duration window, Clock clock) {
        this.max = max;
        this.window = window;
        this.clock = clock;
    }

    /** True if the key has used up its allowance in the current window (nothing is recorded). */
    public boolean isLimited(String key) {
        Deque<Instant> q = events.get(key);
        if (q == null) {
            return false;
        }
        synchronized (q) {
            prune(q);
            return q.size() >= max;
        }
    }

    /** Records one event for the key. */
    public void record(String key) {
        if (events.size() > MAX_KEYS) {
            events.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    prune(e.getValue());
                    return e.getValue().isEmpty();
                }
            });
        }
        Deque<Instant> q = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            q.addLast(clock.instant());
        }
    }

    /** Records an event unless the key is already limited; returns whether it was allowed. */
    public boolean tryAcquire(String key) {
        if (isLimited(key)) {
            return false;
        }
        record(key);
        return true;
    }

    public void reset(String key) {
        events.remove(key);
    }

    private void prune(Deque<Instant> q) {
        Instant cutoff = clock.instant().minus(window);
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
            q.pollFirst();
        }
    }
}
