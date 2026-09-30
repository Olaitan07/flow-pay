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
    FilterRegistrationBean<PathRateLimitFilter> registrationRateLimitFilter(RateLimitProperties properties) {
        return register("registration", "/api/v1/users", properties.registration(), Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Bean
    FilterRegistrationBean<PathRateLimitFilter> loginRateLimitFilter(RateLimitProperties properties) {
        return register("login", "/api/v1/auth/login", properties.login(), Ordered.HIGHEST_PRECEDENCE + 11);
    }

    private FilterRegistrationBean<PathRateLimitFilter> register(String name, String path,
                                                                 RateLimitProperties.Rule rule, int order) {
        var limiter = new FixedWindowRateLimiter(rule.maxRequests(), rule.window(), Clock.systemUTC());
        var registration = new FilterRegistrationBean<>(new PathRateLimitFilter(name, "POST", path, limiter));
        registration.setOrder(order);
        return registration;
    }
}
