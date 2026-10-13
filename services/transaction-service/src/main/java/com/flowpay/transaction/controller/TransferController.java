package com.flowpay.transaction.controller;

import com.flowpay.transaction.dto.request.InitiateTransferRequest;
import com.flowpay.transaction.dto.request.QuoteTransferRequest;
import com.flowpay.transaction.dto.response.ErrorResponse;
import com.flowpay.transaction.dto.response.QuoteResponse;
import com.flowpay.transaction.dto.response.TransferResponse;
import com.flowpay.transaction.exception.TransactionException;
import com.flowpay.transaction.service.TransferService;
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
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/transfers")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "8. P2P transfers",
        description = "Send money to another FlowPay customer. See the fee first with a quote, then send with an Idempotency-Key so "
                + "a retry never charges you twice. All calls need a bearer token and only ever touch your own transfers.")
public class TransferController {

    /** Set by the gateway from a verified access token; the gateway removes any value a client sends. */
    private static final String CUSTOMER = "X-Authenticated-Customer-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/quote")
    @Operation(operationId = "quoteTransfer", summary = "See what a transfer will cost",
            description = """
                    Shows the amount, the fee and the total that would leave your wallet. **Requires a bearer token.** Nothing is moved or recorded.

                    ### How to test
                    1. `POST` `{"amount":10000.00,"currency":"NGN"}`: **200** `amount 10000.00`, `fee 100.00`, `total 10100.00`.
                    2. Amount `0`, negative or with more than 2 decimals: **400**. Without a token: **401**.
                    """)
    @ApiResponse(responseCode = "200", description = "The quote.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public QuoteResponse quote(@Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId,
                               @Valid @RequestBody QuoteTransferRequest request) {
        require(customerId);
        return transferService.quote(request);
    }

    @PostMapping
    @Operation(operationId = "sendTransfer", summary = "Send money to another customer",
            description = """
                    Sends money from your wallet to another customer's wallet. **Requires a bearer token and an `Idempotency-Key` header.**

                    ### How it works
                    * You send `amount`; the recipient receives exactly that. You pay `amount + fee` (see the quote). The money leaves and arrives in **one all-or-nothing step**: it can never leave you without arriving.
                    * Before moving anything it checks: you are not the recipient, your verification level allows this amount (live), you and the recipient both have an `ACTIVE` (recipient may also be `FROZEN`) wallet in this currency, and you can afford `amount + fee`.
                    * **Idempotency-Key** (1 to 100 letters, digits, `. _ : -`) makes a retry safe. Send the same key with the same details again, for example after a timeout, and you get the **original result**; nothing is charged twice. Reuse the key for a different amount or recipient and you get **409**.
                    * `201`: done (`SUCCESS`). `200`: this was a repeat of a transfer that already finished. `202`: recorded but not yet confirmed (`PROCESSING`); it will complete by itself, check it with `GET /api/v1/transfers/{reference}`.
                    * If it fails, **nothing** was taken from your wallet.

                    ### How to test
                    1. Have two verified customers, each with an `NGN` wallet; fund yours with `50000.00`.
                    2. `POST` `{"recipientCustomerId":"<theirs>","amount":10000.00,"currency":"NGN","narration":"Rent"}` with `Idempotency-Key: ABC123`: **201** `SUCCESS`, `fee 100.00`, `total 10100.00`. Your wallet now shows `39900.00`; theirs `10000.00`.
                    3. Send the **same** request again with `ABC123`: **200**, the same `reference`, balances unchanged.
                    4. Send `ABC123` with amount `50000.00`: **409** `IDEMPOTENCY_KEY_REUSED`.
                    5. A new key for `50000.00` (more than you can afford with the fee): **422** `INSUFFICIENT_FUNDS`, balance unchanged.
                    6. Send to yourself: **422** `SELF_TRANSFER`. Unknown recipient: **404** `RECIPIENT_NOT_FOUND`. Amount `0`, negative or more than 2 decimals: **400**. No `Idempotency-Key`: **400**. No token: **401**.
                    7. Not verified, or above your level's limit: **403** `KYC_LEVEL_TOO_LOW` / `KYC_LIMIT_EXCEEDED`.
                    """)
    @ApiResponse(responseCode = "201", description = "The transfer succeeded.")
    @ApiResponse(responseCode = "200", description = "A repeat of a transfer that already finished; nothing moved again.")
    @ApiResponse(responseCode = "202", description = "Recorded, not yet confirmed (PROCESSING). It will complete by itself.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (including a missing or malformed Idempotency-Key).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "KYC_LEVEL_TOO_LOW or KYC_LIMIT_EXCEEDED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND (yours) or RECIPIENT_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_KEY_REUSED: the key was used for a different transfer.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "SELF_TRANSFER, WALLET_NOT_ACTIVE, INSUFFICIENT_FUNDS, RECIPIENT_NOT_ACCEPTING_FUNDS or UNSUPPORTED_CURRENCY. Nothing was taken.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "WALLET_UNAVAILABLE or KYC_UNAVAILABLE: a check could not be made; nothing was recorded. Safe to retry.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<TransferResponse> send(
            @Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId,
            @Parameter(description = "Your own unique key for this transfer, 1 to 100 letters, digits, . _ : -. Send the same key to retry safely.",
                    example = "ABC123", required = true)
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody InitiateTransferRequest request) {
        UUID sender = require(customerId);
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new TransactionException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Idempotency-Key header is required: 1 to 100 letters, digits, . _ : -");
        }
        var outcome = transferService.initiate(sender, idempotencyKey, request);
        TransferResponse body = outcome.transfer();
        return switch (outcome.result()) {
            case CREATED -> ResponseEntity.created(URI.create("/api/v1/transfers/" + body.reference())).body(body);
            case REPLAYED -> ResponseEntity.ok(body);
            case IN_PROGRESS -> ResponseEntity.accepted().location(URI.create("/api/v1/transfers/" + body.reference())).body(body);
        };
    }

    @GetMapping
    @Operation(operationId = "listMyTransfers", summary = "List my transfers",
            description = """
                    Transfers you sent and transfers you received, newest first. **Requires a bearer token.** `limit` defaults to 20 (1 to 100).
                    Each has `direction`: `SENT` or `RECEIVED`. You only see a transfer you received once it has completed, and never its fee.

                    ### How to test
                    1. Send a transfer: it appears for you as `SENT`. The recipient sees the same transfer as `RECEIVED`, without the fee.
                    2. A new account gets an empty list. Without a token: **401**.
                    """)
    @ApiResponse(responseCode = "200", description = "Your transfers (possibly none).")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public List<TransferResponse> list(@Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId,
                                       @RequestParam(defaultValue = "20") int limit) {
        return transferService.list(require(customerId), limit);
    }

    @GetMapping("/{reference}")
    @Operation(operationId = "viewTransfer", summary = "See how a transfer is going",
            description = """
                    Shows one transfer you sent or received. **Requires a bearer token.**

                    ### How it works
                    * `SUCCESS`: the money moved. `FAILED`: nothing was taken from your wallet (see `failureReason`). `PROCESSING`: recorded, waiting to be confirmed; it completes by itself.
                    * Someone else's transfer returns **404**, the same as one that does not exist. A transfer you received that has not completed is also **404**.

                    ### How to test
                    1. Send a transfer and view it by its `reference`: **200**, `SUCCESS`.
                    2. An unknown reference or someone else's: **404** `TRANSFER_NOT_FOUND`. Without a token: **401**.
                    """)
    @ApiResponse(responseCode = "200", description = "The transfer.")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "TRANSFER_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public TransferResponse view(@Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId,
                                 @PathVariable String reference) {
        return transferService.get(require(customerId), reference);
    }

    private static UUID require(UUID customerId) {
        if (customerId == null) {
            throw TransactionException.unauthenticated();
        }
        return customerId;
    }
}
