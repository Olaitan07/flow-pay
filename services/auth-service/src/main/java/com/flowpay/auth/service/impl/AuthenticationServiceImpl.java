package com.flowpay.auth.service.impl;

import com.flowpay.auth.configuration.SecurityProperties;
import com.flowpay.auth.dto.request.CreateCredentialRequest;
import com.flowpay.auth.dto.request.LoginRequest;
import com.flowpay.auth.entity.Credential;
import com.flowpay.auth.exception.DuplicateCredentialException;
import com.flowpay.auth.exception.InvalidCredentialsException;
import com.flowpay.auth.repository.CredentialRepository;
import com.flowpay.auth.service.AuthenticationService;
import com.flowpay.auth.service.LoginOutcome;
import com.flowpay.auth.service.MfaService;
import com.flowpay.auth.service.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationServiceImpl implements AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationServiceImpl.class);

    private final CredentialRepository credentials;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final MfaService mfaService;
    private final SecurityProperties.Login loginPolicy;
    private final Clock clock;
    /** Compared against when no real hash applies, so unknown and known accounts take equally long. */
    private final String decoyHash;

    public AuthenticationServiceImpl(CredentialRepository credentials, PasswordEncoder passwordEncoder,
                                     TokenService tokenService, MfaService mfaService, SecurityProperties properties,
                                     Clock clock) {
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.mfaService = mfaService;
        this.loginPolicy = properties.login();
        this.clock = clock;
        this.decoyHash = passwordEncoder.encode("decoy-password-for-timing");
    }

    /**
     * Deliberately not transactional: a failed attempt must be saved even though we then reject the login,
     * and each counter update is its own atomic statement.
     */
    @Override
    public LoginOutcome login(LoginRequest request) {
        Instant now = clock.instant();
        Optional<Credential> found = credentials.findByEmail(request.email().trim().toLowerCase(Locale.ROOT));

        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), decoyHash);
            log.warn("Failed login: unknown account");
            throw new InvalidCredentialsException();
        }
        Credential credential = found.get();

        if (credential.isLockedAt(now)) {
            passwordEncoder.matches(request.password(), decoyHash);
            log.warn("Login rejected: account locked customerId={}", credential.getCustomerId());
            throw new InvalidCredentialsException();
        }

        if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
            credentials.recordFailedAttempt(credential.getCustomerId(), loginPolicy.maxFailedAttempts(),
                    now.plus(loginPolicy.lockDuration()), now);
            log.warn("Failed login: wrong password customerId={}", credential.getCustomerId());
            throw new InvalidCredentialsException();
        }

        if (credential.isMfaEnabled()) {
            // The failure counter is NOT reset yet: only a completed second step proves the whole login.
            return mfaService.startLoginChallenge(credential);
        }
        credentials.resetFailedAttempts(credential.getCustomerId(), now);
        log.info("Login succeeded customerId={}", credential.getCustomerId());
        return new LoginOutcome.Authenticated(tokenService.issueNewSession(credential.getCustomerId()));
    }

    @Override
    public boolean createCredential(CreateCredentialRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        Optional<Credential> existing = credentials.findById(request.customerId());
        if (existing.isPresent()) {
            if (existing.get().getEmail().equals(email)) {
                return false; // the caller is retrying a request that already succeeded
            }
            throw new DuplicateCredentialException();
        }
        if (credentials.existsByEmail(email)) {
            throw new DuplicateCredentialException();
        }
        try {
            credentials.saveAndFlush(new Credential(request.customerId(), email,
                    passwordEncoder.encode(request.password()), clock.instant()));
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateCredentialException();
        }
        log.info("Credentials created customerId={}", request.customerId());
        return true;
    }
}
