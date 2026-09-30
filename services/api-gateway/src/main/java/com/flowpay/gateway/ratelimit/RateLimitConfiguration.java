package com.flowpay.gateway.ratelimit;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    FilterRegistrationBean<RegistrationRateLimitFilter> registrationRateLimitFilter(RateLimitProperties properties) {
        var limiter = new FixedWindowRateLimiter(properties.maxRequests(), properties.window(), Clock.systemUTC());
        var registration = new FilterRegistrationBean<>(new RegistrationRateLimitFilter(limiter));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
