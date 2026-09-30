package com.flowpay.auth.service.impl;

import com.flowpay.auth.configuration.SecurityProperties;
import com.flowpay.auth.dto.response.TokenResponse;
import com.flowpay.auth.entity.Credential;
import com.flowpay.auth.entity.OtpChallenge;
import com.flowpay.auth.entity.OtpPurpose;
import com.flowpay.auth.exception.InvalidCredentialsException;
import com.flowpay.auth.exception.InvalidMfaCodeException;
import com.flowpay.auth.exception.MfaAlreadyEnabledException;
import com.flowpay.auth.exception.MfaUnavailableException;
import com.flowpay.auth.exception.NotificationFailedException;
import com.flowpay.auth.exception.AuthenticationRequiredException;
import com.flowpay.auth.repository.CredentialRepository;
import com.flowpay.auth.repository.OtpChallengeRepository;
import com.flowpay.auth.service.LoginOutcome;
import com.flowpay.auth.service.MfaService;
import com.flowpay.auth.service.NotificationClient;
import com.flowpay.auth.service.TokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Email one-time codes. A code is 6 digits, valid for a few minutes, allows a handful of guesses, and works
 * once. Wrong guesses also count towards the account lockout, so a stolen password cannot be turned into
 * unlimited code guessing.
 */
@Service
public class MfaServiceImpl implements MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CredentialRepository credentials;
    private final OtpChallengeRepository challenges;
    private final NotificationClient notifications;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties.Otp otpPolicy;
    private final SecurityProperties.Login loginPolicy;
    private final Clock clock;

    public MfaServiceImpl(CredentialRepository credentials, OtpChallengeRepository challenges,
                          NotificationClient notifications, TokenService tokenService, PasswordEncoder passwordEncoder,
                          SecurityProperties properties, Clock clock) {
        this.credentials = credentials;
        this.challenges = challenges;
        this.notifications = notifications;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.otpPolicy = properties.otp();
        this.loginPolicy = properties.login();
        this.clock = clock;
    }

    @Override
    public LoginOutcome.MfaRequired startLoginChallenge(Credential credential) {
        return startChallenge(credential, OtpPurpose.LOGIN, NotificationClient.Type.LOGIN_OTP);
    }

    @Override
    public TokenResponse completeLogin(UUID challengeId, String code) {
        OtpChallenge challenge = verify(challengeId, code, OtpPurpose.LOGIN, null);
        credentials.resetFailedAttempts(challenge.getCustomerId(), clock.instant());
        log.info("Login completed with one-time code customerId={}", challenge.getCustomerId());
        return tokenService.issueNewSession(challenge.getCustomerId());
    }

    @Override
    public LoginOutcome.MfaRequired requestEnable(UUID customerId) {
        Credential credential = credentials.findById(customerId).orElseThrow(AuthenticationRequiredException::new);
        if (credential.isMfaEnabled()) {
            throw new MfaAlreadyEnabledException();
        }
        return startChallenge(credential, OtpPurpose.ENABLE_MFA, NotificationClient.Type.MFA_ENABLE_OTP);
    }

    @Override
    public void confirmEnable(UUID customerId, UUID challengeId, String code) {
        verify(challengeId, code, OtpPurpose.ENABLE_MFA, customerId);
        credentials.setMfaEnabled(customerId, true, clock.instant());
        log.info("Two-step verification enabled customerId={}", customerId);
    }

    @Override
    public void disable(UUID customerId, String password) {
        Credential credential = credentials.findById(customerId).orElseThrow(AuthenticationRequiredException::new);
        Instant now = clock.instant();
        // A stolen access token alone must not be enough, and this endpoint must not become a password oracle.
        if (credential.isLockedAt(now) || !passwordEncoder.matches(password, credential.getPasswordHash())) {
            credentials.recordFailedAttempt(customerId, loginPolicy.maxFailedAttempts(),
                    now.plus(loginPolicy.lockDuration()), now);
            throw new InvalidCredentialsException();
        }
        credentials.setMfaEnabled(customerId, false, now);
        log.info("Two-step verification disabled customerId={}", customerId);
    }

    private LoginOutcome.MfaRequired startChallenge(Credential credential, OtpPurpose purpose,
                                                    NotificationClient.Type emailType) {
        Instant now = clock.instant();
        UUID challengeId = UUID.randomUUID();
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));

        challenges.invalidateOutstanding(credential.getCustomerId(), purpose, now);
        challenges.save(new OtpChallenge(challengeId, credential.getCustomerId(), purpose,
                hash(challengeId, code), now.plus(otpPolicy.ttl()), now));
        try {
            notifications.send(emailType, credential.getEmail(), Map.of(
                    "code", code, "expiresInMinutes", String.valueOf(otpPolicy.ttl().toMinutes())));
        } catch (NotificationFailedException ex) {
            challenges.invalidateOutstanding(credential.getCustomerId(), purpose, now);
            throw new MfaUnavailableException(ex);
        }
        return new LoginOutcome.MfaRequired(challengeId, otpPolicy.ttl().toSeconds());
    }

    /**
     * Checks the code and uses the challenge up. Every failure looks the same to the caller: unknown challenge,
     * wrong purpose or owner, wrong code, expired, used, out of attempts, locked account.
     */
    private OtpChallenge verify(UUID challengeId, String code, OtpPurpose purpose, UUID expectedCustomerId) {
        Instant now = clock.instant();
        OtpChallenge challenge = challenges.findById(challengeId).orElseThrow(InvalidMfaCodeException::new);
        if (challenge.getPurpose() != purpose
                || (expectedCustomerId != null && !expectedCustomerId.equals(challenge.getCustomerId()))) {
            throw new InvalidMfaCodeException();
        }
        Credential credential = credentials.findById(challenge.getCustomerId())
                .orElseThrow(InvalidMfaCodeException::new);
        if (credential.isLockedAt(now)) {
            throw new InvalidMfaCodeException();
        }

        boolean codeMatches = MessageDigest.isEqual(
                hash(challengeId, code).getBytes(StandardCharsets.UTF_8),
                challenge.getCodeHash().getBytes(StandardCharsets.UTF_8));
        if (!codeMatches) {
            challenges.recordFailedAttempt(challengeId);
            credentials.recordFailedAttempt(credential.getCustomerId(), loginPolicy.maxFailedAttempts(),
                    now.plus(loginPolicy.lockDuration()), now);
            log.warn("Wrong one-time code customerId={}", credential.getCustomerId());
            throw new InvalidMfaCodeException();
        }
        if (challenges.consumeIfActive(challengeId, now, otpPolicy.maxAttempts()) != 1) {
            throw new InvalidMfaCodeException();
        }
        return challenge;
    }

    /** Salted with the challenge id so identical codes do not share a hash. */
    private static String hash(UUID challengeId, String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((challengeId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
