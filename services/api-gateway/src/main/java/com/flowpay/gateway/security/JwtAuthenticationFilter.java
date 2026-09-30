package com.flowpay.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Single place where access tokens are checked. Everything except a short list of public endpoints needs a
 * valid, unexpired token from the expected issuer. The customer id from the token is passed to services in
 * {@code X-Authenticated-Customer-Id}; a client-supplied value is always discarded.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /** "METHOD path" pairs that do not need a token. */
    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "POST /api/v1/users",             // registration
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/refresh",      // authenticated by the refresh token in the body
            "POST /api/v1/auth/logout",       // same
            "GET /actuator/health");

    private final JwtDecoder decoder;

    public JwtAuthenticationFilter(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isPublic(request)) {
            chain.doFilter(new AuthenticatedRequest(request, null), response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            reject(request, response);
            return;
        }
        try {
            Jwt jwt = decoder.decode(authorization.substring(7).trim());
            String customerId = jwt.getSubject();
            if (customerId == null || customerId.isBlank()) {
                reject(request, response);
                return;
            }
            chain.doFilter(new AuthenticatedRequest(request, customerId), response);
        } catch (JwtException ex) {
            log.warn("Rejected access token on {}: {}", request.getRequestURI(), ex.getClass().getSimpleName());
            reject(request, response);
        }
    }

    private static boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return PUBLIC_ENDPOINTS.contains(request.getMethod() + " " + path);
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // One generic message for missing, malformed, expired and forged tokens.
        response.getWriter().write("""
                {"timestamp":"%s","status":401,"error":"UNAUTHENTICATED",\
                "message":"Authentication is required","path":"%s"}"""
                .formatted(Instant.now(), request.getRequestURI()));
    }
}
