package com.flowpay.auth.service.impl;

import com.flowpay.auth.configuration.SecurityProperties;
import com.flowpay.auth.dto.response.TokenResponse;
import com.flowpay.auth.entity.RefreshToken;
import com.flowpay.auth.exception.InvalidRefreshTokenException;
import com.flowpay.auth.repository.RefreshTokenRepository;
import com.flowpay.auth.service.TokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TokenServiceImpl implements TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokens;
    private final JwtEncoder jwtEncoder;
    private final SecurityProperties.Jwt jwtProperties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public TokenServiceImpl(RefreshTokenRepository refreshTokens, JwtEncoder jwtEncoder,
                            SecurityProperties properties, TransactionTemplate transaction, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = properties.jwt();
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public TokenResponse issueNewSession(UUID customerId) {
        return issue(customerId, UUID.randomUUID());
    }

    @Override
    public TokenResponse refresh(String rawToken) {
        String hash = sha256(rawToken);
        Instant now = clock.instant();

        RefreshToken token = refreshTokens.findByTokenHash(hash).orElseThrow(InvalidRefreshTokenException::new);
        if (token.getRevokedAt() != null) {
            // A used token was presented again: it may have been stolen. End the whole session.
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh token reuse detected, session revoked customerId={}", token.getCustomerId());
            throw new InvalidRefreshTokenException();
        }
        if (!token.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException();
        }

        // Using the token and issuing its replacement succeed or fail together. The conditional update lets
        // only one of several simultaneous callers win.
        TokenResponse response = transaction.execute(status ->
                refreshTokens.consumeIfActive(hash, now) == 1 ? issue(token.getCustomerId(), token.getFamilyId()) : null);
        if (response == null) {
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            log.warn("Concurrent refresh of one token, session revoked customerId={}", token.getCustomerId());
            throw new InvalidRefreshTokenException();
        }
        return response;
    }

    @Override
    public void revokeSession(String rawToken) {
        refreshTokens.findByTokenHash(sha256(rawToken))
                .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    @Override
    public void revokeAllSessions(UUID customerId) {
        refreshTokens.revokeAllForCustomer(customerId, clock.instant());
        log.info("All sessions revoked customerId={}", customerId);
    }

    private TokenResponse issue(UUID customerId, UUID familyId) {
        Instant now = clock.instant();
        String rawRefreshToken = newOpaqueToken();
        refreshTokens.save(new RefreshToken(customerId, familyId, sha256(rawRefreshToken),
                now.plus(jwtProperties.refreshTokenTtl()), now));

        // The access token carries only the customer id: no email or other personal data.
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(customerId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("flowpay-1").build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return TokenResponse.bearer(accessToken, jwtProperties.accessTokenTtl().toSeconds(), rawRefreshToken);
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
