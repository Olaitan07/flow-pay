package com.flowpay.transaction.controller;

import com.flowpay.transaction.dto.request.InitiateFundingRequest;
import com.flowpay.transaction.dto.response.ErrorResponse;
import com.flowpay.transaction.dto.response.FundingResponse;
import com.flowpay.transaction.exception.TransactionException;
import com.flowpay.transaction.service.FundingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/funding")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "7. Wallet funding",
        description = "Add money to your wallet. You ask to fund it, pay through the chosen source, and your wallet is credited "
                + "once the payment is confirmed. All calls need a bearer token and only ever touch your own fundings.")
public class FundingController {

    /** Set by the gateway from a verified access token; the gateway removes any value a client sends. */
    private static final String CUSTOMER = "X-Authenticated-Customer-Id";

    private final FundingService fundingService;

    public FundingController(FundingService fundingService) {
        this.fundingService = fundingService;
    }

    @PostMapping
    @Operation(operationId = "startFunding", summary = "Add money to my wallet",
            description = """
                    Records a request to add money to one of your wallets and gives you a unique `reference`. **Requires a bearer token.**

                    ### How it works
                    * This call **does not add money**. It creates the funding as `PENDING`. Your wallet is credited only after the payment is confirmed.
                    * The wallet must be yours, in the same currency, and able to receive money (not `BLOCKED` or `CLOSED`).
                    * Your verification level must allow it: funding needs KYC level 1, and the amount must be within your level's limit. This is checked live every time.
                    * `source` is `BANK_TRANSFER`, `CARD` or `EXTERNAL_PROVIDER`.
                    * Follow the funding with `GET /api/v1/funding/{reference}`. Statuses: `PENDING`, `PROCESSING`, `SUCCESS` (money is in your wallet), `FAILED` (nothing was added), `REQUIRES_REVIEW` (our team is checking; your money is safe).

                    ### How to test
                    1. Open a wallet and make sure you are verified to level 1.
                    2. Call this with your `walletId`, `{"amount":5000.00,"currency":"NGN","source":"BANK_TRANSFER"}`: **201**, status `PENDING`, with a `Location` header. Your wallet balance has **not** changed.
                    3. Complete the payment with the internal confirm endpoint (it plays the payment provider), then view the funding: `SUCCESS`, and the wallet shows the money.
                    4. Try someone else's wallet id: **404**. Wrong currency: **422** `CURRENCY_MISMATCH`.
                    5. Try an amount above your level's limit: **403** `KYC_LIMIT_EXCEEDED`. While unverified: **403** `KYC_LEVEL_TOO_LOW`.
                    6. Amount `0`, negative or with more than 2 decimals: **400**. Without a token: **401**.
                    """)
    @ApiResponse(responseCode = "201", description = "The funding was recorded as PENDING.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR or MALFORMED_REQUEST.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "KYC_LEVEL_TOO_LOW or KYC_LIMIT_EXCEEDED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND (or it belongs to someone else).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "CURRENCY_MISMATCH or WALLET_NOT_ACCEPTING_FUNDS.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "WALLET_UNAVAILABLE or KYC_UNAVAILABLE: a check could not be made; nothing was recorded.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<FundingResponse> start(
            @Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId,
            @Valid @RequestBody InitiateFundingRequest request) {
        FundingResponse created = fundingService.initiate(require(customerId), request);
        return ResponseEntity.created(URI.create("/api/v1/funding/" + created.reference())).body(created);
    }

    @GetMapping
    @Operation(operationId = "listMyFundings", summary = "List my fundings",
            description = """
                    Your funding requests, newest first. **Requires a bearer token.** `limit` defaults to 20 (1 to 100).

                    ### How to test
                    1. Start two fundings, then call this: both appear, newest first.
                    2. A new account gets an empty list. Without a token: **401**.
                    """)
    @ApiResponse(responseCode = "200", description = "Your fundings (possibly none).")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public List<FundingResponse> list(@Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId,
                                      @RequestParam(defaultValue = "20") int limit) {
        return fundingService.list(require(customerId), limit);
    }

    @GetMapping("/{reference}")
    @Operation(operationId = "viewFunding", summary = "See how a funding is going",
            description = """
                    Shows one of your fundings. **Requires a bearer token.**

                    ### How it works
                    * `PENDING`: waiting for your payment to be confirmed. `PROCESSING`: payment received, crediting your wallet.
                    * `SUCCESS`: your wallet has the money. `FAILED`: nothing was added to your wallet (see `failureReason`).
                    * `REQUIRES_REVIEW`: something needs a person to check. Your money is not lost.
                    * Another customer's funding returns **404**, the same as one that does not exist.

                    ### How to test
                    1. Start a funding and view it by its `reference`: **200**, `PENDING`.
                    2. An unknown reference or someone else's: **404** `FUNDING_NOT_FOUND`. Without a token: **401**.
                    """)
    @ApiResponse(responseCode = "200", description = "The funding.")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "FUNDING_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public FundingResponse view(@Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId,
                                @PathVariable String reference) {
        return fundingService.get(require(customerId), reference);
    }

    private static UUID require(UUID customerId) {
        if (customerId == null) {
            throw TransactionException.unauthenticated();
        }
        return customerId;
    }
}
