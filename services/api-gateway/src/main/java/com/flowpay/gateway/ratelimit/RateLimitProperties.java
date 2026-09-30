package com.flowpay.gateway.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowpay.rate-limit.registration")
public record RateLimitProperties(Integer maxRequests, Duration window) {

    public RateLimitProperties {
        if (maxRequests == null) maxRequests = 5;
        if (window == null) window = Duration.ofMinutes(1);
    }
}
