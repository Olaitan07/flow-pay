package com.flowpay.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowpay.auth.AbstractIntegrationTest;
import com.flowpay.auth.repository.CredentialRepository;
import com.flowpay.auth.repository.RefreshTokenRepository;
import com.jayway.jsonpath.JsonPath;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class AuthenticationIntegrationTest extends AbstractIntegrationTest {

    static final String EMAIL = "ada@example.com";
    static final String PASSWORD = "Sup3rSecret";

    @Autowired MockMvc mockMvc;
    @Autowired CredentialRepository credentials;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired JdbcTemplate jdbc;

    UUID customerId;

    @BeforeEach
    void createCustomerCredentials() throws Exception {
        refreshTokens.deleteAll();
        credentials.deleteAll();
        customerId = UUID.randomUUID();
        createCredential(INTERNAL_API_KEY, customerId, EMAIL, PASSWORD).andExpect(status().isCreated());
    }

    ResultActions createCredential(String apiKey, UUID id, String email, String password) throws Exception {
        var request = post("/internal/credentials").contentType(MediaType.APPLICATION_JSON).content("""
                {"customerId":"%s","email":"%s","password":"%s"}""".formatted(id, email, password));
        if (apiKey != null) request.header("X-Internal-Api-Key", apiKey);
        return mockMvc.perform(request);
    }

    ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(email, password)));
    }

    ResultActions refresh(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(token)));
    }

    String json(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    // ---- credential creation (called by user-service)

    @Test
    void creatingCredentialsRequiresTheInternalApiKey() throws Exception {
        createCredential(null, UUID.randomUUID(), "x@example.com", PASSWORD).andExpect(status().isUnauthorized());
        createCredential("wrong-key", UUID.randomUUID(), "x@example.com", PASSWORD)
                .andExpect(status().isUnauthorized());
        assertThat(credentials.count()).isEqualTo(1);
    }

    @Test
    void duplicateEmailIsRejected_butRetryOfTheSameCustomerIsHarmless() throws Exception {
        createCredential(INTERNAL_API_KEY, UUID.randomUUID(), EMAIL.toUpperCase(), PASSWORD)
                .andExpect(status().isConflict());
        createCredential(INTERNAL_API_KEY, customerId, EMAIL, PASSWORD).andExpect(status().isOk());
        assertThat(credentials.count()).isEqualTo(1);
    }

    @Test
    void passwordIsStoredOnlyAsABcryptHash() {
        var stored = credentials.findById(customerId).orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2").doesNotContain(PASSWORD);
    }

    // ---- login

    @Test
    void validLoginReturnsSignedTokenForTheCustomer() throws Exception {
        var result = login("Ada@Example.com", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        Jwt jwt = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build()
                .decode(json(result, "$.accessToken"));
        assertThat(jwt.getSubject()).isEqualTo(customerId.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("flowpay-auth");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getClaims()).doesNotContainKeys("email", "password");
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameAnswer() throws Exception {
        String wrongPassword = login(EMAIL, "Wrong1234").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login("nobody@example.com", "Wrong1234").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(wrongPassword, "$.error")).isEqualTo("INVALID_CREDENTIALS");
        assertThat(JsonPath.<String>read(wrongPassword, "$.message"))
                .isEqualTo(JsonPath.<String>read(unknownEmail, "$.message"));
        assertThat(JsonPath.<String>read(wrongPassword, "$.error"))
                .isEqualTo(JsonPath.<String>read(unknownEmail, "$.error"));
    }

    @Test
    void fiveFailuresLockTheAccount_evenForTheCorrectPassword_withoutRevealingIt() throws Exception {
        for (int i = 0; i < 5; i++) login(EMAIL, "Wrong1234").andExpect(status().isUnauthorized());

        login(EMAIL, PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
        assertThat(credentials.findById(customerId).orElseThrow().getLockedUntil()).isAfter(Instant.now());
    }

    @Test
    void lockEndsAfterTheLockDuration() throws Exception {
        for (int i = 0; i < 5; i++) login(EMAIL, "Wrong1234");
        jdbc.update("update credentials set locked_until = now() - interval '1 second'");

        login(EMAIL, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void successfulLoginResetsTheFailureCounter() throws Exception {
        for (int i = 0; i < 4; i++) login(EMAIL, "Wrong1234");
        login(EMAIL, PASSWORD).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) login(EMAIL, "Wrong1234");

        login(EMAIL, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void invalidLoginRequestIs400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    // ---- refresh, logout, revocation

    @Test
    void refreshIssuesNewTokens_andTheUsedTokenStopsWorking() throws Exception {
        String first = json(login(EMAIL, PASSWORD), "$.refreshToken");

        var renewed = refresh(first).andExpect(status().isOk());
        String second = json(renewed, "$.refreshToken");

        assertThat(second).isNotEqualTo(first);
        refresh(first).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void reusingAnOldRefreshTokenEndsTheWholeSession() throws Exception {
        String first = json(login(EMAIL, PASSWORD), "$.refreshToken");
        String second = json(refresh(first), "$.refreshToken");

        refresh(first).andExpect(status().isUnauthorized());

        refresh(second).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownAndExpiredRefreshTokensAreRejected() throws Exception {
        refresh("not-a-real-token").andExpect(status().isUnauthorized());

        String token = json(login(EMAIL, PASSWORD), "$.refreshToken");
        jdbc.update("update refresh_tokens set expires_at = now() - interval '1 minute'");
        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheSession_andIsHarmlessToRepeat() throws Exception {
        String token = json(login(EMAIL, PASSWORD), "$.refreshToken");
        var logout = post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(token));

        mockMvc.perform(logout).andExpect(status().isNoContent());
        mockMvc.perform(logout).andExpect(status().isNoContent());

        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAllRevokesEverySessionOfTheCustomer() throws Exception {
        String phone = json(login(EMAIL, PASSWORD), "$.refreshToken");
        String laptop = json(login(EMAIL, PASSWORD), "$.refreshToken");

        mockMvc.perform(post("/api/v1/auth/logout-all").header("X-Authenticated-Customer-Id", customerId.toString()))
                .andExpect(status().isNoContent());

        refresh(phone).andExpect(status().isUnauthorized());
        refresh(laptop).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAllNeedsAnAuthenticatedCustomer() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout-all")).andExpect(status().isUnauthorized());
    }

    @Test
    void simultaneousRefreshesOfOneTokenNeverBothSucceed() throws Exception {
        String token = json(login(EMAIL, PASSWORD), "$.refreshToken");
        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return refresh(token).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 200) succeeded++;
        }
        pool.shutdown();

        assertThat(succeeded).isLessThanOrEqualTo(1);
    }
}
