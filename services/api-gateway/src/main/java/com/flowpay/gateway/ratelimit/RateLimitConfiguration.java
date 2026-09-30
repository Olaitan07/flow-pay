package com.flowpay.gateway.ratelimit;

import java.time.Clock;
import java.util.Set;
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
        return register("registration", Set.of("/api/v1/users"), properties.registration(), 10);
    }

    /** Login and its second step share one budget, so guessing codes cannot dodge the login limit. */
    @Bean
    FilterRegistrationBean<PathRateLimitFilter> loginRateLimitFilter(RateLimitProperties properties) {
        return register("login", Set.of("/api/v1/auth/login", "/api/v1/auth/login/verify"), properties.login(), 11);
    }

    /** Each reset request sends an email, so unlimited requests would let anyone spam a customer's inbox. */
    @Bean
    FilterRegistrationBean<PathRateLimitFilter> passwordResetRateLimitFilter(RateLimitProperties properties) {
        return register("password-reset", Set.of("/api/v1/auth/password-reset/request",
                "/api/v1/auth/password-reset/confirm"), properties.passwordReset(), 12);
    }

    @Bean
    FilterRegistrationBean<PathRateLimitFilter> mfaRateLimitFilter(RateLimitProperties properties) {
        return register("mfa", Set.of("/api/v1/auth/mfa/enable/request", "/api/v1/auth/mfa/enable/confirm",
                "/api/v1/auth/mfa/disable"), properties.mfa(), 13);
    }

    private FilterRegistrationBean<PathRateLimitFilter> register(String name, Set<String> paths,
                                                                 RateLimitProperties.Rule rule, int offset) {
        var limiter = new FixedWindowRateLimiter(rule.maxRequests(), rule.window(), Clock.systemUTC());
        var registration = new FilterRegistrationBean<>(new PathRateLimitFilter(name, "POST", paths, limiter));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + offset);
        return registration;
    }
}
