package com.flowpay.notification.configuration;

import com.flowpay.notification.security.InternalApiKeyFilter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationConfiguration {

    @Bean
    FilterRegistrationBean<InternalApiKeyFilter> internalApiKeyFilter(
            @Value("${flowpay.security.internal-api-key}") String internalApiKey) {
        var registration = new FilterRegistrationBean<>(new InternalApiKeyFilter(internalApiKey));
        registration.addUrlPatterns("/internal/*");
        return registration;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
