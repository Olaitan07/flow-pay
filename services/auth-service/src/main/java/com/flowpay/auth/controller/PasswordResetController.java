package com.flowpay.auth.controller;

import com.flowpay.auth.dto.request.PasswordResetConfirmRequest;
import com.flowpay.auth.dto.request.PasswordResetRequest;
import com.flowpay.auth.dto.response.ErrorResponse;
import com.flowpay.auth.dto.response.MessageResponse;
import com.flowpay.auth.service.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/password-reset")
@Tag(name = "3. Password reset", description = "Recover an account when the password is forgotten. Both steps are public.")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/request")
    @Operation(operationId = "requestPasswordReset", summary = "Email me a password reset link",
            description = """
                    Asks for a reset link to be emailed. **Public.** Limited to 5 requests per minute per IP address (shared with `confirm`).

                    ### How it works
                    * The answer is **always** `202` with the same message, whether or not the email has an account, so this cannot be used to find out who is registered.
                    * If the account exists, a one-time link valid for **30 minutes** is emailed (`PASSWORD_RESET_URL?token=...`). The email is sent in the background.
                    * Asking again cancels the earlier link; only the newest works.

                    ### How to test
                    1. Register a customer, then call this endpoint with their email. Expect **202**.
                    2. Call it with an email that does not exist. Expect the **identical 202** body.
                    3. Open the inbox (Mailpit at http://localhost:8025). Only the real account received an email. Copy the `token=` value from the link.
                    4. Continue with `POST /api/v1/auth/password-reset/confirm`.
                    """)
    @ApiResponse(responseCode = "202", description = "Accepted. The same response for every email.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<MessageResponse> requestReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new MessageResponse("If an account exists for that email, a reset link has been sent."));
    }

    @PostMapping("/confirm")
    @Operation(operationId = "confirmPasswordReset", summary = "Set a new password using the emailed token",
            description = """
                    Completes a reset. **Public.** The emailed token is the proof of ownership.

                    ### How it works
                    In one transaction: the token is used up (it works **once**), the password is changed, a locked account is **unlocked**, and **every session is signed out**. A "password changed" email is then sent.
                    Unknown, expired and already-used tokens all return the same `400 INVALID_RESET_TOKEN`.
                    A weak new password is rejected **without** using up the token, so you can retry.

                    ### How to test
                    1. Request a reset and copy the `token` from the email.
                    2. Send `token` + a strong `newPassword`. Expect **204**.
                    3. Log in with the new password: **200**. With the old one: **401**.
                    4. Send the same token again: **400 INVALID_RESET_TOKEN**.
                    5. Try an old refresh token: **401** (everyone was signed out).
                    6. Send `newPassword: "weak"` with a fresh token: **400 VALIDATION_ERROR**, and the token still works afterwards.
                    """)
    @ApiResponse(responseCode = "204", description = "Password changed.")
    @ApiResponse(responseCode = "400", description = "INVALID_RESET_TOKEN or VALIDATION_ERROR (weak password).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "429", description = "RATE_LIMIT_EXCEEDED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Void> confirmReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirmReset(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
