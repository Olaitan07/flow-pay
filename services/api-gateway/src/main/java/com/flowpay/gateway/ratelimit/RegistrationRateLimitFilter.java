package com.flowpay.gateway.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Throttles customer registration per client address. Registration reveals whether an email or phone
 * number is already taken, so unlimited attempts would let an attacker harvest registered customers.
 */
public class RegistrationRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RegistrationRateLimitFilter.class);
    private static final String REGISTRATION_PATH = "/api/v1/users";

    private final FixedWindowRateLimiter limiter;

    public RegistrationRateLimitFilter(FixedWindowRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean isRegistration = path.equals(REGISTRATION_PATH) || path.equals(REGISTRATION_PATH + "/");
        return !(isRegistration && HttpMethod.POST.matches(request.getMethod()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // getRemoteAddr, not X-Forwarded-For: that header is client-controlled and would let an
        // attacker dodge the limit. Revisit when the gateway sits behind a trusted load balancer.
        var decision = limiter.tryAcquire(request.getRemoteAddr());
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Registration rate limit exceeded path={}", request.getRequestURI());
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"timestamp":"%s","status":429,"error":"RATE_LIMIT_EXCEEDED",\
                "message":"Too many registration attempts. Try again later.","path":"%s"}"""
                .formatted(Instant.now(), request.getRequestURI()));
    }
}
