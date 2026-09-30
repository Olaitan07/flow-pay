package com.flowpay.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RegistrationRateLimitFilterTest {

    private final RegistrationRateLimitFilter filter = new RegistrationRateLimitFilter(
            new FixedWindowRateLimiter(2, Duration.ofMinutes(1), Clock.systemUTC()));

    private MockHttpServletResponse call(String method, String path, String ip) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void blocksRegistrationPostsOverTheLimitWith429() throws Exception {
        assertThat(call("POST", "/api/v1/users", "9.9.9.9").getStatus()).isEqualTo(200);
        assertThat(call("POST", "/api/v1/users", "9.9.9.9").getStatus()).isEqualTo(200);

        var blocked = call("POST", "/api/v1/users", "9.9.9.9");

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotNull();
        assertThat(blocked.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void ignoresSpoofedForwardedForHeader() throws Exception {
        for (int i = 0; i < 2; i++) call("POST", "/api/v1/users", "9.9.9.9");
        var request = new MockHttpServletRequest("POST", "/api/v1/users");
        request.setRemoteAddr("9.9.9.9");
        request.addHeader("X-Forwarded-For", "8.8.8.8");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void doesNotThrottleOtherEndpointsOrMethods() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(call("GET", "/api/v1/users/123", "9.9.9.9").getStatus()).isEqualTo(200);
            assertThat(call("POST", "/api/v1/wallets", "9.9.9.9").getStatus()).isEqualTo(200);
        }
    }
}
