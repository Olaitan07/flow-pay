package com.flowpay.auth.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowpay.notification-service")
public record NotificationServiceProperties(String url, Duration connectTimeout, Duration readTimeout) {

    public NotificationServiceProperties {
        if (url == null || url.isBlank()) url = "http://localhost:8086";
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(8);
    }
}
