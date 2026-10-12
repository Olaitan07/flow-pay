package com.flowpay.transaction.controller;

import com.flowpay.transaction.dto.request.ConfirmFundingRequest;
import com.flowpay.transaction.dto.response.ErrorResponse;
import com.flowpay.transaction.dto.response.FundingDetailResponse;
import com.flowpay.transaction.service.FundingProcessor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Provider callbacks and staff operations. Never routed by the gateway; guarded by the internal API key. */
@RestController
@RequestMapping("/internal/funding")
@Validated
@SecurityRequirement(name = "internalApiKey")
@Tag(name = "Internal: funding",
        description = "Not reachable through the gateway. Needs the X-Internal-Api-Key header. Used by the payment provider's callback and by operations staff.")
public class InternalFundingController {

    private static final String ACTOR = "X-Actor";

    private final FundingProcessor processor;

    public InternalFundingController(FundingProcessor processor) {
        this.processor = processor;
    }

    @GetMapping("/{reference}")
    @Operation(operationId = "getFundingForOperations", summary = "Open a funding with its full history",
            description = """
                    Shows any funding with provider details, the review reason (if any) and every state change, oldest first. **Internal only.**

                    ### How to test (direct call, development only)
                    1. Call with `X-Internal-Api-Key` and a funding reference: **200**.
                    2. Unknown reference: **404**. Missing key: **401**.
                    """)
    @ApiResponse(responseCode = "200", description = "The funding and its history.")
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "FUNDING_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public FundingDetailResponse detail(@PathVariable String reference) {
        return processor.detail(reference);
    }

    @PostMapping("/{reference}/confirm")
    @Operation(operationId = "confirmFunding", summary = "The payment provider reports the payment's result",
            description = """
                    How the payment provider (or, in development, you) tells FlowPay that a payment succeeded or failed. **Internal only.** In production this is the provider's signed callback.

                    ### How it works
                    * `SUCCESS` needs the `amount` and `currency` the provider actually received. If they equal the funding's, the funding moves to `PROCESSING`, the wallet is credited, and it becomes `SUCCESS`. The wallet's money and the funding's record always agree.
                    * **Different amount or currency:** nothing is credited. The funding becomes `REQUIRES_REVIEW` (`AMOUNT_MISMATCH`) so a person can decide.
                    * `FAILED` marks the funding `FAILED`. The wallet is never touched, so the spendable balance does not go up.
                    * **The wallet service is down:** the payment is still recorded and the funding stays `PROCESSING`. A background job finishes it as soon as the wallet service is back. No money is lost or credited twice.
                    * **The wallet refuses the money** (for example it was blocked or closed): `REQUIRES_REVIEW` (`CREDIT_REFUSED`).
                    * **Repeats are safe.** Sending the same confirmation again returns the current state and moves nothing twice. A `FAILED` funding can never become `SUCCESS`, and a `SUCCESS` one can never become `FAILED`.

                    ### How to test (direct call, development only)
                    1. Start a funding, then confirm with `{"outcome":"SUCCESS","amount":5000.00,"currency":"NGN","providerReference":"PSK_1"}`: **200**, `SUCCESS`; the wallet now holds the money.
                    2. Send the same confirmation again: **200**, still `SUCCESS`, balance unchanged.
                    3. Start another and confirm `{"outcome":"FAILED","failureReason":"Insufficient funds"}`: `FAILED`, wallet unchanged. Then try `SUCCESS` on it: **409** `FUNDING_ALREADY_FAILED`.
                    4. Confirm a funding for `4000.00` when `5000.00` was requested: `REQUIRES_REVIEW`, wallet unchanged.
                    5. `SUCCESS` without `amount`/`currency`: **400**. Unknown reference: **404**.
                    """)
    @ApiResponse(responseCode = "200", description = "The funding after processing the report (or unchanged, if it was a repeat).")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "FUNDING_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "FUNDING_ALREADY_FAILED or FUNDING_STATE_CONFLICT: the report contradicts a final state.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public FundingDetailResponse confirm(@PathVariable String reference, @Valid @RequestBody ConfirmFundingRequest request) {
        return processor.confirm(reference, request);
    }

    @PostMapping("/{reference}/retry")
    @Operation(operationId = "retryFunding", summary = "Try again to credit a stuck or refused funding",
            description = """
                    Tries again to put a confirmed payment into the wallet. **Internal only.** Needs `X-Actor`, which is kept in the audit trail.

                    ### How it works
                    * Works on a `PROCESSING` funding, and on a `REQUIRES_REVIEW` one whose reason is `CREDIT_REFUSED` (for example after the wallet is unblocked).
                    * It cannot retry an `AMOUNT_MISMATCH` review, or a final (`SUCCESS`/`FAILED`) funding: **422** `FUNDING_NOT_RETRYABLE`.
                    * Safe to repeat: the wallet credits a funding's reference only once.

                    ### How to test (direct call, development only)
                    1. Block a wallet and confirm a funding for it: `REQUIRES_REVIEW`.
                    2. Reactivate the wallet and call retry with `X-Actor: ops.jane`: the funding becomes `SUCCESS` and the wallet holds the money.
                    3. Retry a `SUCCESS` funding: **422**. Omit `X-Actor`: **400**.
                    """)
    @ApiResponse(responseCode = "200", description = "The funding after the retry.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: missing X-Actor.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "FUNDING_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "FUNDING_NOT_RETRYABLE.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public FundingDetailResponse retry(
            @PathVariable String reference,
            @Parameter(description = "Who is retrying. Recorded in the audit trail.", example = "ops.jane")
            @RequestHeader(ACTOR) @NotBlank @Size(max = 100) String actor) {
        return processor.retry(reference, actor.trim());
    }
}
