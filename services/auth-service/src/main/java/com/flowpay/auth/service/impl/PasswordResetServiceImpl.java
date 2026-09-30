package com.flowpay.auth.service.impl;

import com.flowpay.auth.configuration.SecurityProperties;
import com.flowpay.auth.entity.Credential;
import com.flowpay.auth.entity.PasswordResetToken;
import com.flowpay.auth.exception.InvalidResetTokenException;
import com.flowpay.auth.repository.CredentialRepository;
import com.flowpay.auth.repository.PasswordResetTokenRepository;
import com.flowpay.auth.repository.RefreshTokenRepository;
import com.flowpay.auth.service.NotificationClient;
import com.flowpay.auth.service.PasswordResetService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordResetServiceImpl implements PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CredentialRepository credentials;
    private final PasswordResetTokenRepository resetTokens;
    private final RefreshTokenRepository refreshTokens;
    private final NotificationClient notifications;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;
    private final Executor notificationExecutor;
    private final SecurityProperties.PasswordReset policy;
    private final Clock clock;

    public PasswordResetServiceImpl(CredentialRepository credentials, PasswordResetTokenRepository resetTokens,
                                    RefreshTokenRepository refreshTokens, NotificationClient notifications,
                                    PasswordEncoder passwordEncoder, TransactionTemplate transaction,
                                    @Qualifier("notificationExecutor") Executor notificationExecutor,
                                    SecurityProperties properties, Clock clock) {
        this.credentials = credentials;
        this.resetTokens = resetTokens;
        this.refreshTokens = refreshTokens;
        this.notifications = notifications;
        this.passwordEncoder = passwordEncoder;
        this.transaction = transaction;
        this.notificationExecutor = notificationExecutor;
        this.policy = properties.passwordReset();
        this.clock = clock;
    }

    /**
     * The request thread does the same work whether or not the email has an account (one lookup). Creating the
     * token and sending the email happen in the background, so neither the response nor its timing reveals
     * which emails are registered.
     */
    @Override
    public void requestReset(String email) {
        Optional<Credential> credential = credentials.findByEmail(email.trim().toLowerCase(Locale.ROOT));
        if (credential.isEmpty()) {
            log.info("Password reset requested for unknown email");
            return;
        }
        try {
            notificationExecutor.execute(() -> issueAndSend(credential.get()));
        } catch (RejectedExecutionException ex) {
            log.error("Password reset dropped: notification queue is full");
        }
    }

    @Override
    public void confirmReset(String rawToken, String newPassword) {
        String tokenHash = sha256(rawToken);
        Instant now = clock.instant();

        PasswordResetToken token = resetTokens.findByTokenHash(tokenHash).orElseThrow(InvalidResetTokenException::new);
        if (token.getUsedAt() != null || !token.getExpiresAt().isAfter(now)) {
            throw new InvalidResetTokenException();
        }
        String newHash = passwordEncoder.encode(newPassword); // slow by design, so done before the transaction

        // Using the link, changing the password, unlocking and signing out every device succeed or fail together.
        // The conditional update lets only one of several simultaneous callers win.
        Boolean done = transaction.execute(status -> {
            if (resetTokens.consumeIfActive(tokenHash, now) != 1) {
                return false;
            }
            credentials.updatePasswordHash(token.getCustomerId(), newHash, now);
            credentials.resetFailedAttempts(token.getCustomerId(), now);
            refreshTokens.revokeAllForCustomer(token.getCustomerId(), now);
            resetTokens.invalidateOutstanding(token.getCustomerId(), now);
            return true;
        });
        if (!Boolean.TRUE.equals(done)) {
            throw new InvalidResetTokenException();
        }
        log.info("Password reset completed customerId={}", token.getCustomerId());

        credentials.findById(token.getCustomerId()).ifPresent(credential -> runInBackground(
                () -> notifications.send(NotificationClient.Type.PASSWORD_CHANGED, credential.getEmail(), Map.of())));
    }

    private void issueAndSend(Credential credential) {
        try {
            Instant now = clock.instant();
            String rawToken = newOpaqueToken();
            resetTokens.invalidateOutstanding(credential.getCustomerId(), now);
            resetTokens.save(new PasswordResetToken(credential.getCustomerId(), sha256(rawToken),
                    now.plus(policy.tokenTtl()), now));
            String link = policy.url() + (policy.url().contains("?") ? "&" : "?") + "token="
                    + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
            notifications.send(NotificationClient.Type.PASSWORD_RESET, credential.getEmail(), Map.of(
                    "resetLink", link, "expiresInMinutes", String.valueOf(policy.tokenTtl().toMinutes())));
            log.info("Password reset email sent customerId={}", credential.getCustomerId());
        } catch (RuntimeException ex) {
            log.error("Password reset email failed customerId={} cause={}", credential.getCustomerId(),
                    ex.getClass().getSimpleName());
        }
    }

    private void runInBackground(Runnable task) {
        try {
            notificationExecutor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException ex) {
                    log.error("Background notification failed cause={}", ex.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException ex) {
            log.error("Notification dropped: queue is full");
        }
    }

    private static String newOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
