package com.flowpay.gateway.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allows at most {@code maxRequests} per key in each time window. State is held in memory, so the limit
 * applies per gateway instance; move it to Redis (Epic 23) before running several gateway replicas.
 */
public class FixedWindowRateLimiter {

    private static final int PRUNE_THRESHOLD = 10_000;

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private record Window(Instant start, int count) {
    }

    private final int maxRequests;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int maxRequests, Duration window, Clock clock) {
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
    }

    public Decision tryAcquire(String key) {
        Instant now = clock.instant();
        if (windows.size() > PRUNE_THRESHOLD) {
            windows.entrySet().removeIf(e -> !e.getValue().start().plus(window).isAfter(now));
        }
        Window updated = windows.compute(key, (k, current) -> {
            if (current == null || !current.start().plus(window).isAfter(now)) {
                return new Window(now, 1);
            }
            return new Window(current.start(), current.count() + 1);
        });
        if (updated.count() <= maxRequests) {
            return new Decision(true, 0);
        }
        long retryAfter = Math.max(1, Duration.between(now, updated.start().plus(window)).toSeconds() + 1);
        return new Decision(false, retryAfter);
    }
}
