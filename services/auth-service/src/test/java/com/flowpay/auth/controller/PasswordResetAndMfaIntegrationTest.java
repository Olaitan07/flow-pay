package com.flowpay.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowpay.auth.AbstractIntegrationTest;
import com.flowpay.auth.exception.NotificationFailedException;
import com.flowpay.auth.repository.CredentialRepository;
import com.flowpay.auth.repository.OtpChallengeRepository;
import com.flowpay.auth.repository.PasswordResetTokenRepository;
import com.flowpay.auth.repository.RefreshTokenRepository;
import com.flowpay.auth.service.NotificationClient;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

class PasswordResetAndMfaIntegrationTest extends AbstractIntegrationTest {

    static final String EMAIL = "ada@example.com";
    static final String PASSWORD = "Sup3rSecret";
    static final String NEW_PASSWORD = "Brand9NewPass";
    static final String CUSTOMER_HEADER = "X-Authenticated-Customer-Id";

    @Autowired MockMvc mockMvc;
    @Autowired CredentialRepository credentials;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired PasswordResetTokenRepository resetTokens;
    @Autowired OtpChallengeRepository challenges;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("notificationExecutor") ThreadPoolTaskExecutor notificationExecutor;

    UUID customerId;

    @BeforeEach
    void setUp() throws Exception {
        // A previous test may have left a background email job running; let it finish before wiping the tables.
        await().atMost(Duration.ofSeconds(10)).until(() ->
                notificationExecutor.getActiveCount() == 0 && notificationExecutor.getThreadPoolExecutor().getQueue().isEmpty());
        reset(notificationClient);
        jdbc.update("delete from otp_challenges");
        jdbc.update("delete from password_reset_tokens");
        refreshTokens.deleteAll();
        credentials.deleteAll();
        customerId = UUID.randomUUID();
        mockMvc.perform(post("/internal/credentials").header("X-Internal-Api-Key", INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}"
                                .formatted(customerId, EMAIL, PASSWORD)))
                .andExpect(status().isCreated());
    }

    // ---------- helpers

    ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions postAs(UUID customer, String path, String json) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (customer != null) request.header(CUSTOMER_HEADER, customer.toString());
        return mockMvc.perform(request);
    }

    ResultActions login(String password) throws Exception {
        return postJson("/api/v1/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, password));
    }

    ResultActions verifyLogin(String challengeId, String code) throws Exception {
        return postJson("/api/v1/auth/login/verify", "{\"challengeId\":\"%s\",\"code\":\"%s\"}".formatted(challengeId, code));
    }

    static String field(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    /** The variables of the most recent email of this type (waits: some emails are sent in the background). */
    Map<String, String> lastEmail(NotificationClient.Type type) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationClient, timeout(3000).atLeastOnce()).send(eq(type), eq(EMAIL), captor.capture());
        return captor.getAllValues().getLast();
    }

    String tokenFrom(Map<String, String> email) {
        URI link = URI.create(email.get("resetLink"));
        return UriComponentsBuilder.fromUri(link).build().getQueryParams().getFirst("token");
    }

    String requestResetAndGetToken() throws Exception {
        reset(notificationClient);
        postJson("/api/v1/auth/password-reset/request", "{\"email\":\"%s\"}".formatted(EMAIL))
                .andExpect(status().isAccepted());
        return tokenFrom(lastEmail(NotificationClient.Type.PASSWORD_RESET));
    }

    ResultActions confirmReset(String token, String newPassword) throws Exception {
        return postJson("/api/v1/auth/password-reset/confirm",
                "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, newPassword));
    }

    void enableMfa() throws Exception {
        String challengeId = field(postAs(customerId, "/api/v1/auth/mfa/enable/request", "{}")
                .andExpect(status().isOk()), "$.challengeId");
        String code = lastEmail(NotificationClient.Type.MFA_ENABLE_OTP).get("code");
        postAs(customerId, "/api/v1/auth/mfa/enable/confirm",
                "{\"challengeId\":\"%s\",\"code\":\"%s\"}".formatted(challengeId, code))
                .andExpect(status().isNoContent());
        reset(notificationClient);
    }

    // ---------- password reset

    @Test
    void resetRequest_emailsALinkWithASingleUseTokenStoredOnlyAsAHash() throws Exception {
        String token = requestResetAndGetToken();

        assertThat(token).hasSizeGreaterThan(30);
        assertThat(lastEmail(NotificationClient.Type.PASSWORD_RESET).get("resetLink")).startsWith("http");
        assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens where token_hash = ?",
                Integer.class, token)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    void resetRequest_looksIdenticalForUnknownEmails_andSendsNothing() throws Exception {
        String known = postJson("/api/v1/auth/password-reset/request", "{\"email\":\"%s\"}".formatted(EMAIL))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        lastEmail(NotificationClient.Type.PASSWORD_RESET); // the background send has finished
        reset(notificationClient);
        String unknown = postJson("/api/v1/auth/password-reset/request", "{\"email\":\"nobody@example.com\"}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(unknown).isEqualTo(known);
        verify(notificationClient, after(500).never()).send(any(), any(), any());
        assertThat(jdbc.queryForObject("select count(*) from password_reset_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    void resetRequest_stillAnswers202_whenTheEmailCannotBeSent() throws Exception {
        doThrow(new NotificationFailedException("down", null)).when(notificationClient).send(any(), any(), any());

        postJson("/api/v1/auth/password-reset/request", "{\"email\":\"%s\"}".formatted(EMAIL))
                .andExpect(status().isAccepted());
    }

    @Test
    void confirmingWithTheLink_changesThePassword_signsOutEveryDevice_andNotifiesTheOwner() throws Exception {
        String refreshToken = field(login(PASSWORD).andExpect(status().isOk()), "$.refreshToken");
        String token = requestResetAndGetToken();

        confirmReset(token, NEW_PASSWORD).andExpect(status().isNoContent());

        login(NEW_PASSWORD).andExpect(status().isOk());
        login(PASSWORD).andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"%s\"}".formatted(refreshToken))
                .andExpect(status().isUnauthorized());
        verify(notificationClient, timeout(3000)).send(eq(NotificationClient.Type.PASSWORD_CHANGED), eq(EMAIL), any());
        assertThat(credentials.findById(customerId).orElseThrow().getPasswordHash()).startsWith("$2")
                .doesNotContain(NEW_PASSWORD);
    }

    @Test
    void aResetLinkWorksOnlyOnce() throws Exception {
        String token = requestResetAndGetToken();
        confirmReset(token, NEW_PASSWORD).andExpect(status().isNoContent());

        confirmReset(token, "An0therPassw0rd").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_RESET_TOKEN"));

        login(NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aNewRequestCancelsTheEarlierLink() throws Exception {
        String first = requestResetAndGetToken();
        String second = requestResetAndGetToken();

        confirmReset(first, NEW_PASSWORD).andExpect(status().isBadRequest());
        confirmReset(second, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void expiredUnknownAndWeakPasswordAttemptsAreRejected_andAWeakPasswordDoesNotBurnTheLink() throws Exception {
        String token = requestResetAndGetToken();

        confirmReset("not-a-real-token", NEW_PASSWORD).andExpect(status().isBadRequest());
        confirmReset(token, "weak").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        jdbc.update("update password_reset_tokens set expires_at = now() - interval '1 minute'");
        confirmReset(token, NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_RESET_TOKEN"));

        login(PASSWORD).andExpect(status().isOk());
    }

    @Test
    void weakPasswordLeavesTheLinkUsable() throws Exception {
        String token = requestResetAndGetToken();

        confirmReset(token, "weak").andExpect(status().isBadRequest());

        confirmReset(token, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void resettingThePasswordUnlocksALockedAccount() throws Exception {
        for (int i = 0; i < 5; i++) login("Wrong1234").andExpect(status().isUnauthorized());
        login(PASSWORD).andExpect(status().isUnauthorized());
        String token = requestResetAndGetToken();

        confirmReset(token, NEW_PASSWORD).andExpect(status().isNoContent());

        login(NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void simultaneousUsesOfOneLinkNeverBothSucceed() throws Exception {
        String token = requestResetAndGetToken();
        int attempts = 5;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            String password = "Passw0rdNumber" + i;
            results.add(pool.submit(() -> {
                start.await();
                return confirmReset(token, password).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 204) succeeded++;
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
    }

    // ---------- two-step verification (email OTP)

    @Test
    void enablingRequiresAnEmailedCode_andThenLoginNeedsASecondStep() throws Exception {
        enableMfa();
        assertThat(credentials.findById(customerId).orElseThrow().isMfaEnabled()).isTrue();

        var response = login(PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        String challengeId = field(response, "$.challengeId");
        String code = lastEmail(NotificationClient.Type.LOGIN_OTP).get("code");

        assertThat(code).matches("\\d{6}");
        verifyLogin(challengeId, code).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void theCodeIsNeverStoredInPlainText() throws Exception {
        enableMfa();
        login(PASSWORD);
        String code = lastEmail(NotificationClient.Type.LOGIN_OTP).get("code");

        String stored = jdbc.queryForObject("select code_hash from otp_challenges where purpose = 'LOGIN'", String.class);

        assertThat(stored).hasSize(64).doesNotContain(code);
    }

    @Test
    void aCodeWorksOnlyOnce() throws Exception {
        enableMfa();
        String challengeId = field(login(PASSWORD), "$.challengeId");
        String code = lastEmail(NotificationClient.Type.LOGIN_OTP).get("code");
        verifyLogin(challengeId, code).andExpect(status().isOk());

        verifyLogin(challengeId, code).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_MFA_CODE"));
    }

    @Test
    void wrongCodesAreRejected_andAfterFiveGuessesEvenTheRightCodeIsDead() throws Exception {
        enableMfa();
        String challengeId = field(login(PASSWORD), "$.challengeId");
        String code = lastEmail(NotificationClient.Type.LOGIN_OTP).get("code");
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            verifyLogin(challengeId, wrong).andExpect(status().isUnauthorized());
        }

        verifyLogin(challengeId, code).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCodesCountTowardsTheAccountLockout() throws Exception {
        enableMfa();
        String challengeId = field(login(PASSWORD), "$.challengeId");
        String code = lastEmail(NotificationClient.Type.LOGIN_OTP).get("code");
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) verifyLogin(challengeId, wrong);

        assertThat(credentials.findById(customerId).orElseThrow().getLockedUntil()).isNotNull();
        login(PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredAndUnknownChallengesAreRejectedTheSameWay() throws Exception {
        enableMfa();
        String challengeId = field(login(PASSWORD), "$.challengeId");
        String code = lastEmail(NotificationClient.Type.LOGIN_OTP).get("code");
        jdbc.update("update otp_challenges set expires_at = now() - interval '1 second'");

        verifyLogin(challengeId, code).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_MFA_CODE"));
        verifyLogin(UUID.randomUUID().toString(), "123456").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_MFA_CODE"));
    }

    @Test
    void aNewLoginCodeReplacesTheOldOne() throws Exception {
        enableMfa();
        String first = field(login(PASSWORD), "$.challengeId");
        String firstCode = lastEmail(NotificationClient.Type.LOGIN_OTP).get("code");
        reset(notificationClient);
        login(PASSWORD);
        String secondCode = lastEmail(NotificationClient.Type.LOGIN_OTP).get("code");

        verifyLogin(first, firstCode).andExpect(status().isUnauthorized());
        assertThat(secondCode).isNotNull();
    }

    @Test
    void aLoginCodeCannotBeUsedToEnableMfa_andACodeForAnotherCustomerIsRefused() throws Exception {
        String enableChallenge = field(postAs(customerId, "/api/v1/auth/mfa/enable/request", "{}"), "$.challengeId");
        String code = lastEmail(NotificationClient.Type.MFA_ENABLE_OTP).get("code");

        // wrong purpose
        verifyLogin(enableChallenge, code).andExpect(status().isUnauthorized());
        // wrong customer
        postAs(UUID.randomUUID(), "/api/v1/auth/mfa/enable/confirm",
                "{\"challengeId\":\"%s\",\"code\":\"%s\"}".formatted(enableChallenge, code))
                .andExpect(status().isUnauthorized());

        assertThat(credentials.findById(customerId).orElseThrow().isMfaEnabled()).isFalse();
    }

    @Test
    void ifTheCodeEmailCannotBeSent_loginFailsCleanlyWithoutTokens() throws Exception {
        enableMfa();
        doThrow(new NotificationFailedException("down", null)).when(notificationClient).send(any(), any(), any());

        login(PASSWORD).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("MFA_UNAVAILABLE"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void mfaManagementNeedsASignedInCustomer_andCannotBeEnabledTwice() throws Exception {
        postAs(null, "/api/v1/auth/mfa/enable/request", "{}").andExpect(status().isUnauthorized());
        postAs(null, "/api/v1/auth/mfa/disable", "{\"password\":\"x\"}").andExpect(status().isUnauthorized());
        enableMfa();

        postAs(customerId, "/api/v1/auth/mfa/enable/request", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("MFA_ALREADY_ENABLED"));
    }

    @Test
    void disablingNeedsThePassword_andThenLoginIsSingleStepAgain() throws Exception {
        enableMfa();

        postAs(customerId, "/api/v1/auth/mfa/disable", "{\"password\":\"Wrong1234\"}")
                .andExpect(status().isUnauthorized());
        assertThat(credentials.findById(customerId).orElseThrow().isMfaEnabled()).isTrue();

        postAs(customerId, "/api/v1/auth/mfa/disable", "{\"password\":\"%s\"}".formatted(PASSWORD))
                .andExpect(status().isNoContent());
        login(PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void invalidCodeFormatIs400() throws Exception {
        verifyLogin(UUID.randomUUID().toString(), "12ab56").andExpect(status().isBadRequest());
        verifyLogin(UUID.randomUUID().toString(), "12345").andExpect(status().isBadRequest());
    }
}
