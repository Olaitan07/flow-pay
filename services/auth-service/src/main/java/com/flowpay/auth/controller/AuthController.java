package com.flowpay.auth.controller;

import com.flowpay.auth.dto.request.LoginRequest;
import com.flowpay.auth.dto.request.RefreshTokenRequest;
import com.flowpay.auth.dto.request.VerifyOtpRequest;
import com.flowpay.auth.dto.response.MfaChallengeResponse;
import com.flowpay.auth.dto.response.TokenResponse;
import com.flowpay.auth.exception.AuthenticationRequiredException;
import com.flowpay.auth.service.AuthenticationService;
import com.flowpay.auth.service.LoginOutcome;
import com.flowpay.auth.service.MfaService;
import com.flowpay.auth.service.TokenService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** Set by the gateway from a verified access token; the gateway removes any value a client sends. */
    static final String AUTHENTICATED_CUSTOMER_HEADER = "X-Authenticated-Customer-Id";

    private final AuthenticationService authenticationService;
    private final MfaService mfaService;
    private final TokenService tokenService;

    public AuthController(AuthenticationService authenticationService, MfaService mfaService,
                          TokenService tokenService) {
        this.authenticationService = authenticationService;
        this.mfaService = mfaService;
        this.tokenService = tokenService;
    }

    /** Returns tokens, or {@code mfaRequired: true} with a challenge id when a code was emailed. */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        return switch (authenticationService.login(request)) {
            case LoginOutcome.Authenticated authenticated -> ResponseEntity.ok(authenticated.tokens());
            case LoginOutcome.MfaRequired mfa ->
                    ResponseEntity.ok(MfaChallengeResponse.of(mfa.challengeId(), mfa.expiresInSeconds()));
        };
    }

    @PostMapping("/login/verify")
    public TokenResponse verifyLogin(@Valid @RequestBody VerifyOtpRequest request) {
        return mfaService.completeLogin(request.challengeId(), request.code());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return tokenService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        tokenService.revokeSession(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(
            @RequestHeader(name = AUTHENTICATED_CUSTOMER_HEADER, required = false) UUID customerId) {
        if (customerId == null) {
            throw new AuthenticationRequiredException();
        }
        tokenService.revokeAllSessions(customerId);
        return ResponseEntity.noContent().build();
    }
}
