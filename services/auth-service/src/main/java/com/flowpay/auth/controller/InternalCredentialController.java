package com.flowpay.auth.controller;

import com.flowpay.auth.dto.request.CreateCredentialRequest;
import com.flowpay.auth.dto.response.ErrorResponse;
import com.flowpay.auth.service.AuthenticationService;
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

/** Called by user-service during registration. Protected by the internal API key; not exposed by the gateway. */
@RestController
@RequestMapping("/internal/credentials")
@SecurityRequirement(name = "internalApiKey")
@Tag(name = "Internal: credentials", description = "Service-to-service only. Not reachable through the gateway; call auth-service directly (port 8081) with the X-Internal-Api-Key header.")
public class InternalCredentialController {

    private final AuthenticationService authenticationService;

    public InternalCredentialController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping
    @Operation(operationId = "createCredential", summary = "Store login credentials for a new customer",
            description = """
                    Called by user-service right after it creates a customer. **Internal only.**

                    ### How it works
                    Hashes the password (BCrypt) and stores it with the email. Safe to retry: the same customer id and email returns `200` instead of creating a duplicate.

                    ### How to test (direct call, development only)
                    1. Send any random `customerId` UUID with a new email and a password, with the `X-Internal-Api-Key` header. Expect **201**.
                    2. Repeat the identical request. Expect **200** (idempotent retry).
                    3. Use the same email with a different `customerId`. Expect **409 DUPLICATE_CREDENTIAL**.
                    4. Omit or change the key. Expect **401**.
                    """)
    @ApiResponse(responseCode = "201", description = "Credentials created.")
    @ApiResponse(responseCode = "200", description = "Identical credentials already existed (retry).")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "DUPLICATE_CREDENTIAL: email or customer id already used differently.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Void> createCredential(@Valid @RequestBody CreateCredentialRequest request) {
        boolean created = authenticationService.createCredential(request);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
    }
}
