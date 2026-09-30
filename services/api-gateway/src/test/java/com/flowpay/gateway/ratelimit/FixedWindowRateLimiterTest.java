package com.flowpay.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(3, Duration.ofMinutes(1), clock);

    @Test
    void allowsUpToTheLimitThenBlocksWithRetryAfter() {
        for (int i = 0; i < 3; i++) assertThat(limiter.tryAcquire("1.1.1.1").allowed()).isTrue();

        var blocked = limiter.tryAcquire("1.1.1.1");

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isBetween(1L, 61L);
    }

    @Test
    void countsEachClientSeparately() {
        for (int i = 0; i < 3; i++) limiter.tryAcquire("1.1.1.1");

        assertThat(limiter.tryAcquire("2.2.2.2").allowed()).isTrue();
    }

    @Test
    void allowsAgainOnceTheWindowHasPassed() {
        for (int i = 0; i < 4; i++) limiter.tryAcquire("1.1.1.1");
        clock.now = clock.now.plusSeconds(61);

        assertThat(limiter.tryAcquire("1.1.1.1").allowed()).isTrue();
    }
}
