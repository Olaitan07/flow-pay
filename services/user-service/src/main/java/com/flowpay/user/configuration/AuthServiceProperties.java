package com.flowpay.user.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowpay.auth-service")
public record AuthServiceProperties(String url, Duration connectTimeout, Duration readTimeout) {

    public AuthServiceProperties {
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(5);
    }
}
