package com.flowpay.auth.controller;

import com.flowpay.auth.dto.request.LoginRequest;
import com.flowpay.auth.dto.request.RefreshTokenRequest;
import com.flowpay.auth.dto.request.VerifyOtpRequest;
import com.flowpay.auth.dto.response.ErrorResponse;
import com.flowpay.auth.dto.response.MfaChallengeResponse;
import com.flowpay.auth.dto.response.TokenResponse;
import com.flowpay.auth.exception.AuthenticationRequiredException;
import com.flowpay.auth.service.AuthenticationService;
import com.flowpay.auth.service.LoginOutcome;
import com.flowpay.auth.service.MfaService;
import com.flowpay.auth.service.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "2. Authentication", description = "Sign in, keep a session alive, and sign out.")
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

    @PostMapping("/login")
    @Operation(operationId = "login", summary = "Log in with email and password",
            description = """
                    Signs a customer in. **Public.** Limited to 10 attempts per minute per IP address (shared with `login/verify`).

                    ### How it works
                    * Correct password, two-step verification **off** -> `200` with `accessToken` (JWT, 15 min) and `refreshToken` (7 days).
                    * Correct password, two-step verification **on** -> `200` with `{"mfaRequired": true, "challengeId": ...}` and **no tokens**. A 6-digit code is emailed; finish with `POST /api/v1/auth/login/verify`.
                    * Anything else -> the **same** `401 INVALID_CREDENTIALS` message for a wrong password, an unknown email and a locked account, so the API never reveals whether an account exists.
                    * **5 wrong passwords lock the account for 15 minutes** (even the correct password is refused while locked).

                    ### How to test
                    1. Register a customer first (`POST /api/v1/users`).
                    2. Log in with the right password. Expect **200** and tokens. Copy `accessToken` into the Authorize box.
                    3. Log in with a wrong password. Expect **401 INVALID_CREDENTIALS**. Try an unknown email: the response is identical.
                    4. Fail 5 times in a row, then use the correct password. Still **401** (locked for 15 minutes; a password reset unlocks it).
                    5. Hit it 11 times in a minute. Expect **429**.
                    6. To try the two-step response, enable it under "4. Two-step verification" and log in again.
                    """)
    @ApiResponse(responseCode = "200",
            description = "Tokens, or an MFA challenge when two-step verification is enabled.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(oneOf = {TokenResponse.class, MfaChallengeResponse.class})))
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS (wrong password, unknown email, or locked account).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "MFA_UNAVAILABLE: two-step is on but the code email could not be sent. No tokens issued.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        return switch (authenticationService.login(request)) {
            case LoginOutcome.Authenticated authenticated -> ResponseEntity.ok(authenticated.tokens());
            case LoginOutcome.MfaRequired mfa ->
                    ResponseEntity.ok(MfaChallengeResponse.of(mfa.challengeId(), mfa.expiresInSeconds()));
        };
    }

    @PostMapping("/login/verify")
    @Operation(operationId = "verifyLogin", summary = "Finish login with the emailed code",
            description = """
                    Second step of login for customers who turned on two-step verification. **Public.** Shares the login rate limit.

                    ### How it works
                    The code is 6 digits, valid **5 minutes**, usable **once**, with **5 guesses**. Wrong guesses also count towards the account lockout.
                    Every failure (wrong code, expired, already used, unknown challenge) returns the same `401 INVALID_MFA_CODE`.

                    ### How to test
                    1. Enable two-step verification, then call `POST /api/v1/auth/login`. Copy `challengeId`.
                    2. Open the inbox (Mailpit at http://localhost:8025) and read the 6-digit code.
                    3. Send `challengeId` + `code`. Expect **200** with tokens.
                    4. Send the same code again. Expect **401 INVALID_MFA_CODE** (single use).
                    5. Request a new login code and send `000000` five times. The code is now dead, even the right one.
                    6. A malformed code such as `12ab` returns **400**.
                    """)
    @ApiResponse(responseCode = "200", description = "Login complete.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: challengeId or code malformed.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "INVALID_MFA_CODE.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public TokenResponse verifyLogin(@Valid @RequestBody VerifyOtpRequest request) {
        return mfaService.completeLogin(request.challengeId(), request.code());
    }

    @PostMapping("/refresh")
    @Operation(operationId = "refreshToken", summary = "Get a new access token",
            description = """
                    Exchanges a refresh token for a fresh access token **and a new refresh token**. **Public** (the refresh token is the proof).

                    ### How it works
                    Refresh tokens are **single use** ("rotation"). Using one returns a new pair and retires the old token.
                    If an already-used token is presented again it may have been stolen, so the **whole session is revoked**: every token in that chain stops working.

                    ### How to test
                    1. Log in and copy `refreshToken`.
                    2. Call refresh with it. Expect **200** and a **different** `refreshToken`.
                    3. Call refresh again with the **old** token. Expect **401 INVALID_REFRESH_TOKEN**.
                    4. Now try the **new** token from step 2. Also **401**: reuse ended the session. Log in again.
                    """)
    @ApiResponse(responseCode = "200", description = "A new token pair.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "INVALID_REFRESH_TOKEN: unknown, expired, already used, or revoked.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return tokenService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @Operation(operationId = "logout", summary = "Log out this session",
            description = """
                    Ends the session that the refresh token belongs to. **Public** (the refresh token is the proof).

                    ### How it works
                    The refresh token (and every token in its chain) is revoked. Calling it again, or with an unknown token, still returns `204`, so it is safe to repeat and reveals nothing.
                    The access token already issued stays valid until it expires (at most 15 minutes).

                    ### How to test
                    1. Log in and copy `refreshToken`.
                    2. Call logout with it. Expect **204**.
                    3. Call refresh with the same token. Expect **401**.
                    4. Call logout again. Still **204**.
                    """)
    @ApiResponse(responseCode = "204", description = "Session ended (or there was nothing to end).")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        tokenService.revokeSession(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(operationId = "logoutAll", summary = "Log out everywhere",
            description = """
                    Ends **every** session of the signed-in customer (phone, laptop, ...). **Requires a bearer token.**

                    ### How it works
                    All of the customer's refresh tokens are revoked. Existing access tokens expire on their own within 15 minutes.

                    ### How to test
                    1. Log in twice to create two sessions; keep both `refreshToken` values and one `accessToken`.
                    2. Call this endpoint with the access token. Expect **204**.
                    3. Call refresh with each refresh token. Both return **401**.
                    4. Call it without a token. Expect **401 UNAUTHENTICATED**.
                    """)
    @ApiResponse(responseCode = "204", description = "All sessions ended.")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Void> logoutAll(
            @Parameter(hidden = true)
            @RequestHeader(name = AUTHENTICATED_CUSTOMER_HEADER, required = false) UUID customerId) {
        if (customerId == null) {
            throw new AuthenticationRequiredException();
        }
        tokenService.revokeAllSessions(customerId);
        return ResponseEntity.noContent().build();
    }
}
