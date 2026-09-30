package com.flowpay.gateway.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Throttles one endpoint (method + path) per client address. Used for registration, which reveals whether an
 * email or phone is taken, and for login, where per-account lockout alone does not stop one address trying
 * many accounts.
 */
public class PathRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PathRateLimitFilter.class);

    private final String method;
    private final String path;
    private final String name;
    private final FixedWindowRateLimiter limiter;

    public PathRateLimitFilter(String name, String method, String path, FixedWindowRateLimiter limiter) {
        this.name = name;
        this.method = method;
        this.path = path;
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requested = request.getRequestURI();
        boolean samePath = requested.equals(path) || requested.equals(path + "/");
        return !(samePath && method.equalsIgnoreCase(request.getMethod()));
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
        log.warn("Rate limit exceeded limit={} path={}", name, request.getRequestURI());
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"timestamp":"%s","status":429,"error":"RATE_LIMIT_EXCEEDED",\
                "message":"Too many attempts. Try again later.","path":"%s"}"""
                .formatted(Instant.now(), request.getRequestURI()));
    }
}
