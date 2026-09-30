package com.flowpay.auth.controller;

import com.flowpay.auth.dto.request.DisableMfaRequest;
import com.flowpay.auth.dto.request.VerifyOtpRequest;
import com.flowpay.auth.dto.response.ErrorResponse;
import com.flowpay.auth.dto.response.MfaChallengeResponse;
import com.flowpay.auth.exception.AuthenticationRequiredException;
import com.flowpay.auth.service.LoginOutcome;
import com.flowpay.auth.service.MfaService;
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

/** Turning two-step verification on or off. The caller must be signed in (identity set by the gateway). */
@RestController
@RequestMapping("/api/v1/auth/mfa")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "4. Two-step verification", description = "Optional extra security: after the password, a 6-digit code is emailed. All three calls need a bearer token.")
public class MfaController {

    private static final String CUSTOMER = "X-Authenticated-Customer-Id";

    private final MfaService mfaService;

    public MfaController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    @PostMapping("/enable/request")
    @Operation(operationId = "requestEnableMfa", summary = "Start turning on two-step verification",
            description = """
                    Emails a confirmation code. **Requires a bearer token.** Limited to 10 requests per minute per IP address (shared with the other MFA calls).

                    ### How it works
                    A code is emailed first, so you cannot lock yourself out with an address that does not work. Nothing changes until you confirm the code.
                    Requesting again replaces the previous code. If two-step is already on you get `409`.

                    ### How to test
                    1. Log in and authorize with the `accessToken`.
                    2. Call this endpoint. Expect **200** with a `challengeId`. Copy it.
                    3. Open the inbox (Mailpit at http://localhost:8025) and read the code.
                    4. Continue with `POST /api/v1/auth/mfa/enable/confirm`.
                    5. Without a token: **401**. After enabling, calling this again: **409 MFA_ALREADY_ENABLED**.
                    """)
    @ApiResponse(responseCode = "200", description = "A code was emailed.")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "MFA_ALREADY_ENABLED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "MFA_UNAVAILABLE: the code email could not be sent.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public MfaChallengeResponse requestEnable(
            @Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId) {
        LoginOutcome.MfaRequired challenge = mfaService.requestEnable(require(customerId));
        return MfaChallengeResponse.of(challenge.challengeId(), challenge.expiresInSeconds());
    }

    @PostMapping("/enable/confirm")
    @Operation(operationId = "confirmEnableMfa", summary = "Confirm the code and turn on two-step verification",
            description = """
                    Turns two-step verification on once the emailed code is proven. **Requires a bearer token.**

                    ### How it works
                    The code must belong to **your** account and to this purpose (a login code cannot be used here, and vice versa). Same rules as login codes: 5 minutes, single use, 5 guesses.
                    From then on, `POST /api/v1/auth/login` returns `mfaRequired` instead of tokens.

                    ### How to test
                    1. After `enable/request`, send the `challengeId` and the emailed `code`. Expect **204**.
                    2. Log in again: the response is now `{"mfaRequired": true, ...}`.
                    3. Send a wrong code: **401 INVALID_MFA_CODE**.
                    4. Send a code that belongs to someone else's challenge: also **401**.
                    """)
    @ApiResponse(responseCode = "204", description = "Two-step verification is now on.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED or INVALID_MFA_CODE.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Void> confirmEnable(
            @Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId,
            @Valid @RequestBody VerifyOtpRequest request) {
        mfaService.confirmEnable(require(customerId), request.challengeId(), request.code());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/disable")
    @Operation(operationId = "disableMfa", summary = "Turn off two-step verification",
            description = """
                    Turns two-step verification off. **Requires a bearer token and the current password.**

                    ### How it works
                    The password is required so that a stolen access token alone cannot weaken the account. Wrong passwords here count towards the account lockout.

                    ### How to test
                    1. With two-step on, send a wrong password. Expect **401 INVALID_CREDENTIALS** (still enabled).
                    2. Send the correct password. Expect **204**.
                    3. Log in: you get tokens directly again.
                    """)
    @ApiResponse(responseCode = "204", description = "Two-step verification is now off.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED or INVALID_CREDENTIALS (wrong password).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Void> disable(
            @Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId,
            @Valid @RequestBody DisableMfaRequest request) {
        mfaService.disable(require(customerId), request.password());
        return ResponseEntity.noContent().build();
    }

    private static UUID require(UUID customerId) {
        if (customerId == null) {
            throw new AuthenticationRequiredException();
        }
        return customerId;
    }
}
