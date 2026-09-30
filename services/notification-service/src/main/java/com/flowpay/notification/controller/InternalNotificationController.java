package com.flowpay.notification.controller;

import com.flowpay.notification.dto.request.SendEmailRequest;
import com.flowpay.notification.dto.response.ErrorResponse;
import com.flowpay.notification.dto.response.NotificationResponse;
import com.flowpay.notification.service.EmailNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Called by other FlowPay services. Protected by the internal API key; not exposed by the gateway. */
@RestController
@RequestMapping("/internal/notifications")
@SecurityRequirement(name = "internalApiKey")
@Tag(name = "Internal: notifications", description = "Service-to-service only. Not reachable through the gateway; call notification-service directly (port 8086) with the X-Internal-Api-Key header.")
public class InternalNotificationController {

    private final EmailNotificationService emailNotificationService;

    public InternalNotificationController(EmailNotificationService emailNotificationService) {
        this.emailNotificationService = emailNotificationService;
    }

    @PostMapping("/email")
    @Operation(operationId = "sendEmail", summary = "Send a templated email",
            description = """
                    Sends one email using a built-in template. **Internal only.**

                    ### How it works
                    The template decides the subject and wording; you supply the variables (for example the code or link). The send is logged as `SENT` or `FAILED` **without the message body**, because it can hold codes.
                    Required variables: `PASSWORD_RESET` -> `resetLink`, `expiresInMinutes`; `LOGIN_OTP` and `MFA_ENABLE_OTP` -> `code`, `expiresInMinutes`; `PASSWORD_CHANGED` -> none.

                    ### How to test (direct call, development only)
                    1. Send `{"type":"LOGIN_OTP","recipient":"test@example.com","variables":{"code":"123456","expiresInMinutes":"5"}}` with the `X-Internal-Api-Key` header. Expect **201**.
                    2. Open Mailpit at http://localhost:8025 and read the email.
                    3. Omit `code`: **400 INVALID_NOTIFICATION**. Use a recipient containing a line break: **400**.
                    4. Omit or change the key: **401**.
                    """)
    @ApiResponse(responseCode = "201", description = "Email handed to the mail server.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR or INVALID_NOTIFICATION (missing template variable).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "DELIVERY_FAILED: the mail server did not accept the message (recorded as FAILED).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<NotificationResponse> sendEmail(@Valid @RequestBody SendEmailRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(emailNotificationService.sendEmail(request));
    }
}
