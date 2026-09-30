package com.flowpay.gateway.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowpay.rate-limit")
public record RateLimitProperties(Rule registration, Rule login, Rule passwordReset, Rule mfa) {

    public RateLimitProperties {
        if (registration == null) registration = new Rule(null, null);
        if (login == null) login = new Rule(10, null);
        if (passwordReset == null) passwordReset = new Rule(5, null);
        if (mfa == null) mfa = new Rule(10, null);
    }

    public record Rule(Integer maxRequests, Duration window) {
        public Rule {
            if (maxRequests == null) maxRequests = 5;
            if (window == null) window = Duration.ofMinutes(1);
        }
    }
}
