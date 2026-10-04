package com.flowpay.kyc.controller;

import com.flowpay.kyc.dto.request.SubmitTierOneRequest;
import com.flowpay.kyc.dto.request.SubmitTierTwoRequest;
import com.flowpay.kyc.dto.response.ErrorResponse;
import com.flowpay.kyc.dto.response.KycStatusResponse;
import com.flowpay.kyc.dto.response.SubmissionResponse;
import com.flowpay.kyc.exception.AuthenticationRequiredException;
import com.flowpay.kyc.service.KycSubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/kyc")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "5. Identity verification (KYC)",
        description = "Verify your identity to unlock more of FlowPay. Three levels: 0 (just registered), 1 (date of birth + address), "
                + "2 (government ID). All calls need a bearer token and only ever touch your own data.")
public class KycController {

    /** Set by the gateway from a verified access token; the gateway removes any value a client sends. */
    private static final String CUSTOMER = "X-Authenticated-Customer-Id";

    private final KycSubmissionService submissionService;

    public KycController(KycSubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @GetMapping
    @Operation(operationId = "getKycStatus", summary = "See my verification status",
            description = """
                    Shows where each tier stands and what your current level allows. **Requires a bearer token.**

                    ### How it works
                    * `level` is the highest tier that is `VERIFIED` (0 if none). It is read live from the database on every call.
                    * Each tier is `NOT_STARTED`, `PENDING` (waiting for a reviewer), `REQUIRES_REVIEW` (an extra check is needed), `VERIFIED` or `REJECTED` (with the reviewer's reason).
                    * `capabilities` lists the operations your level allows and the largest single transaction.

                    ### How to test
                    1. Log in and authorize with your `accessToken`.
                    2. Call this endpoint on a new account. Expect **200**, `level: 0`, both tiers `NOT_STARTED`, and no allowed operations.
                    3. Submit tier 1, then call it again: tier 1 is `PENDING`.
                    4. After a reviewer approves (see the internal review endpoints), `level` becomes 1 and the allowed operations appear.
                    5. Without a token: **401**.
                    """)
    @ApiResponse(responseCode = "200", description = "Your verification status.")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public KycStatusResponse status(@Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId) {
        return submissionService.getStatus(require(customerId));
    }

    @PostMapping("/tiers/1")
    @Operation(operationId = "submitTierOne", summary = "Submit tier 1: date of birth and address",
            description = """
                    Starts tier 1 verification. **Requires a bearer token.**

                    ### How it works
                    * You must be **at least 18** (otherwise `422 KYC_NOT_ELIGIBLE`).
                    * The submission is saved as `PENDING` and waits for a human reviewer. Nothing is verified automatically.
                    * You can have **one** open tier 1 submission at a time (`409 KYC_ALREADY_SUBMITTED`), and cannot resubmit once verified (`409 KYC_ALREADY_VERIFIED`).
                    * If a reviewer **rejects** it you may submit again.
                    * When it is approved your level becomes 1 and your account moves from `PENDING_VERIFICATION` to `ACTIVE` (this happens a moment later, through an event).

                    ### How to test
                    1. Send the example body. Expect **201** with status `PENDING`.
                    2. Send it again. Expect **409 KYC_ALREADY_SUBMITTED**.
                    3. Use a date of birth less than 18 years ago on a fresh account. Expect **422 KYC_NOT_ELIGIBLE**.
                    4. Send `{}` or a bad country such as `Nigeria`. Expect **400 VALIDATION_ERROR**.
                    5. Ask a reviewer to approve it (`POST /internal/kyc/submissions/{id}/decision`), then check `GET /api/v1/kyc` and your profile.
                    """)
    @ApiResponse(responseCode = "201", description = "Submitted and waiting for review.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR or MALFORMED_REQUEST.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "KYC_ALREADY_SUBMITTED or KYC_ALREADY_VERIFIED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "KYC_NOT_ELIGIBLE: under 18.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<SubmissionResponse> submitTierOne(
            @Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId,
            @Valid @RequestBody SubmitTierOneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(submissionService.submitTierOne(require(customerId), request));
    }

    @PostMapping("/tiers/2")
    @Operation(operationId = "submitTierTwo", summary = "Submit tier 2: government ID",
            description = """
                    Starts tier 2 verification with a government-issued ID. **Requires a bearer token and a verified tier 1.**

                    ### How it works
                    * Tier 1 must already be `VERIFIED`, otherwise `422 KYC_TIER_PREREQUISITE`.
                    * **NIN** and **BVN** must be exactly 11 digits. Spaces and dashes in the number are ignored.
                    * The ID number is **encrypted** before it is stored and is shown back to you **masked** (`*******8901`). No document images are collected.
                    * Saved as `PENDING` for a human reviewer. If the same ID has already been submitted by another customer it is silently marked `REQUIRES_REVIEW` for a closer look (you are not told why).
                    * One open submission at a time (`409`), and you cannot resubmit once verified (`409`).

                    ### How to test
                    1. Before tier 1 is verified, send the example body. Expect **422 KYC_TIER_PREREQUISITE**.
                    2. After tier 1 is approved, send it again. Expect **201** with status `PENDING` and a masked number.
                    3. Send `{"idType":"NIN","idNumber":"123"}`. Expect **400** (too short). Send a 10-digit NIN: **400** (must be 11).
                    4. Send it again while pending: **409 KYC_ALREADY_SUBMITTED**.
                    5. After approval, `level` becomes 2.
                    """)
    @ApiResponse(responseCode = "201", description = "Submitted and waiting for review.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR or MALFORMED_REQUEST.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "KYC_ALREADY_SUBMITTED or KYC_ALREADY_VERIFIED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "KYC_TIER_PREREQUISITE: verify tier 1 first.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<SubmissionResponse> submitTierTwo(
            @Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId,
            @Valid @RequestBody SubmitTierTwoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(submissionService.submitTierTwo(require(customerId), request));
    }

    private static UUID require(UUID customerId) {
        if (customerId == null) {
            throw new AuthenticationRequiredException();
        }
        return customerId;
    }
}
