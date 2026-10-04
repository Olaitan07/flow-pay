package com.flowpay.kyc.controller;

import com.flowpay.kyc.dto.request.AuthorizeOperationRequest;
import com.flowpay.kyc.dto.request.ReviewDecisionRequest;
import com.flowpay.kyc.dto.response.AuthorizationDecision;
import com.flowpay.kyc.dto.response.CapabilitiesResponse;
import com.flowpay.kyc.dto.response.DecisionResponse;
import com.flowpay.kyc.dto.response.ErrorResponse;
import com.flowpay.kyc.dto.response.ReviewDetailResponse;
import com.flowpay.kyc.dto.response.ReviewQueueItem;
import com.flowpay.kyc.service.KycPolicyService;
import com.flowpay.kyc.service.KycReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff review and service-to-service checks. Guarded by the internal API key; the gateway does not route /internal.
 * Real staff accounts and roles arrive with the security and admin epics; until then the reviewer is a name
 * supplied in X-Reviewer, recorded in the audit trail.
 */
@RestController
@RequestMapping("/internal/kyc")
@Validated
@SecurityRequirement(name = "internalApiKey")
@Tag(name = "Internal: KYC", description = "Reviewer and service-to-service endpoints. Not reachable through the gateway; "
        + "call kyc-service directly (port 8087) with the X-Internal-Api-Key header.")
public class InternalKycController {

    private static final String REVIEWER = "X-Reviewer";

    private final KycReviewService reviewService;
    private final KycPolicyService policyService;

    public InternalKycController(KycReviewService reviewService, KycPolicyService policyService) {
        this.reviewService = reviewService;
        this.policyService = policyService;
    }

    @GetMapping("/submissions")
    @Operation(operationId = "listReviewQueue", summary = "List submissions waiting for review",
            description = """
                    The reviewer's work queue, oldest first. **Internal only.**

                    ### How it works
                    Returns `PENDING` and `REQUIRES_REVIEW` submissions. ID numbers are **masked** in this list; open one submission to see the full details.

                    ### How to test (direct call, development only)
                    1. A customer submits tier 1 or 2 (`POST /api/v1/kyc/tiers/1`).
                    2. Call this endpoint with the `X-Internal-Api-Key` header. The submission appears, status `PENDING`.
                    3. Submit the same ID number from a second customer: that one appears as `REQUIRES_REVIEW`.
                    4. Omit the key: **401**.
                    """)
    @ApiResponse(responseCode = "200", description = "The queue.")
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public List<ReviewQueueItem> queue(
            @Parameter(description = "Zero-based page.", example = "0") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size, 1-100.", example = "20") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reviewService.queue(page, size);
    }

    @GetMapping("/submissions/{id}")
    @Operation(operationId = "getSubmissionForReview", summary = "Open a submission (shows the full ID number)",
            description = """
                    Everything a reviewer needs to decide, including the **decrypted ID number**. **Internal only.** Requires `X-Reviewer`.

                    ### How it works
                    Every call is written to the audit trail as `SENSITIVE_DATA_VIEWED` with the reviewer's name, so it is always known who looked at an ID number and when.
                    The response includes the submission's full history, oldest first.

                    ### How to test (direct call, development only)
                    1. Take an id from the review queue.
                    2. Call this endpoint with `X-Internal-Api-Key` and `X-Reviewer: reviewer.jane`. Expect **200** with the full number.
                    3. Call it again: the `history` now has two `SENSITIVE_DATA_VIEWED` lines.
                    4. Omit `X-Reviewer`: **400**. Unknown id: **404**.
                    """)
    @ApiResponse(responseCode = "200", description = "The submission, with sensitive data.")
    @ApiResponse(responseCode = "400", description = "X-Reviewer header missing.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "KYC_SUBMISSION_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ReviewDetailResponse detail(
            @PathVariable UUID id,
            @Parameter(description = "Who is looking. Recorded in the audit trail.", example = "reviewer.jane")
            @RequestHeader(REVIEWER) @NotBlank @Size(max = 100) String reviewer) {
        return reviewService.detail(id, reviewer.trim());
    }

    @PostMapping("/submissions/{id}/decision")
    @Operation(operationId = "decideSubmission", summary = "Approve or reject a submission",
            description = """
                    Records the reviewer's decision. **Internal only.** Requires `X-Reviewer`.

                    ### How it works
                    * `APPROVE` -> the tier becomes `VERIFIED` and the customer's level rises. An event (`KYC_LEVEL_CHANGED`, ids only) is published to Kafka so other services react; user-service then moves the account from `PENDING_VERIFICATION` to `ACTIVE`.
                    * `REJECT` -> `REJECTED`. A **reason is required** and is shown to the customer, who may then submit again.
                    * The decision, its audit line and the event are saved in **one transaction**: none can exist without the others.
                    * A submission can be decided **once**. If two reviewers act at the same moment exactly one succeeds; the other gets `409 KYC_NOT_REVIEWABLE`.

                    ### How to test (direct call, development only)
                    1. Submit tier 1 as a customer, take its id from the queue.
                    2. `POST` `{"decision":"APPROVE"}` with the key and `X-Reviewer`. Expect **200** with `status: VERIFIED` and `customerLevel: 1`.
                    3. `GET /api/v1/kyc` as the customer: level is 1. A few seconds later their profile status is `ACTIVE`.
                    4. Decide the same submission again: **409 KYC_NOT_REVIEWABLE**.
                    5. Reject without a `reason`: **400**.
                    """)
    @ApiResponse(responseCode = "200", description = "Decision recorded.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (e.g. missing reason on REJECT, missing X-Reviewer).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "KYC_SUBMISSION_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "KYC_NOT_REVIEWABLE: already decided.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public DecisionResponse decide(
            @PathVariable UUID id,
            @Parameter(description = "Who is deciding. Recorded in the audit trail.", example = "reviewer.jane")
            @RequestHeader(REVIEWER) @NotBlank @Size(max = 100) String reviewer,
            @Valid @RequestBody ReviewDecisionRequest request) {
        return reviewService.decide(id, reviewer.trim(), request);
    }

    @GetMapping("/customers/{customerId}/capabilities")
    @Operation(operationId = "getCustomerCapabilities", summary = "What may this customer do?",
            description = """
                    Returns a customer's current verification level and what it allows. **Internal only.**

                    ### How it works
                    Read live from the database every time (never from a token or cache), so it cannot be stale. A customer with no verified tier is level 0.

                    ### How to test (direct call, development only)
                    1. Call it for a new customer: level 0, no operations.
                    2. Approve their tier 1: level 1 allows `FUND_WALLET`, `P2P_TRANSFER` up to 50,000.00 NGN.
                    3. Approve tier 2: level 2 also allows `EXTERNAL_TRANSFER`, up to 5,000,000.00 NGN.
                    """)
    @ApiResponse(responseCode = "200", description = "The customer's capabilities.")
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public CapabilitiesResponse capabilities(@PathVariable UUID customerId) {
        return policyService.capabilitiesOf(customerId);
    }

    @PostMapping("/customers/{customerId}/authorize")
    @Operation(operationId = "authorizeOperation", summary = "May this customer do this, for this amount?",
            description = """
                    The enforcement point. Wallet and transfer services call this **before** moving money, so the rule holds whether the request came from an app, a browser or a direct API call. **Internal only.**

                    ### How it works
                    Checks, in order: the currency is supported (`UNSUPPORTED_CURRENCY`), the customer's level allows the operation (`KYC_LEVEL_TOO_LOW`, with `requiredLevel`), and the amount is within the level's single-transaction cap (`KYC_LIMIT_EXCEEDED`).
                    A refusal is a normal `200` with `allowed: false`: the caller must stop and surface the reason. Amounts are decimals with at most 2 places; the currency is always explicit.

                    ### How to test (direct call, development only)
                    1. Level 0 customer, `FUND_WALLET` 1000.00 NGN: `allowed: false`, `KYC_LEVEL_TOO_LOW`, `requiredLevel: 1`.
                    2. After tier 1: `P2P_TRANSFER` 10000.00 NGN is allowed; 60000.00 is `KYC_LIMIT_EXCEEDED`; `EXTERNAL_TRANSFER` is `KYC_LEVEL_TOO_LOW` (`requiredLevel: 2`).
                    3. After tier 2: all three are allowed up to the higher cap.
                    4. Currency `USD`: `UNSUPPORTED_CURRENCY`. Amount `-5` or `1.234`: **400**.
                    """)
    @ApiResponse(responseCode = "200", description = "The decision (check `allowed`).")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (amount or currency invalid).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public AuthorizationDecision authorize(@PathVariable UUID customerId, @Valid @RequestBody AuthorizeOperationRequest request) {
        return policyService.authorize(customerId, request);
    }
}
