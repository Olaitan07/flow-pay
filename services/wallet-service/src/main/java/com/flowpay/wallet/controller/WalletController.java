package com.flowpay.wallet.controller;

import com.flowpay.wallet.dto.request.CreateWalletRequest;
import com.flowpay.wallet.dto.response.ErrorResponse;
import com.flowpay.wallet.dto.response.WalletResponse;
import com.flowpay.wallet.exception.WalletException;
import com.flowpay.wallet.service.WalletService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallets")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "6. Wallets",
        description = "Hold your money in a wallet. You need a verified identity (KYC level 1) to open one. "
                + "All calls need a bearer token and only ever touch your own wallets.")
public class WalletController {

    /** Set by the gateway from a verified access token; the gateway removes any value a client sends. */
    private static final String CUSTOMER = "X-Authenticated-Customer-Id";

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    @Operation(operationId = "createWallet", summary = "Open a wallet",
            description = """
                    Opens an empty, `ACTIVE` wallet in one currency. **Requires a bearer token.**

                    ### How it works
                    * Only supported currencies are accepted (currently `NGN`). Anything else is refused.
                    * You must be verified to **level 1** or higher. This is checked live against the KYC service every time, never from the token.
                    * One live wallet per currency. A closed wallet does not stop you opening a new one.
                    * A new wallet has `ledgerBalance` and `availableBalance` of `0.00`. The available balance can never exceed the ledger balance and can never go below zero.

                    ### How to test
                    1. Log in and authorize with your `accessToken`.
                    2. While still level 0, call this with `{"currency":"NGN"}`: **403** `KYC_LEVEL_TOO_LOW`.
                    3. Get verified to level 1 (see the KYC folder), then call again: **201** with a `Location` header and an `ACTIVE` wallet.
                    4. Call it again: **409** `WALLET_ALREADY_EXISTS`.
                    5. Try `{"currency":"USD"}`: **422** `UNSUPPORTED_CURRENCY`. Try `{"currency":"ngn"}` or `{}`: **400** `VALIDATION_ERROR`.
                    6. Without a token: **401**.
                    """)
    @ApiResponse(responseCode = "201", description = "The new wallet.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: the currency is missing or malformed.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "KYC_LEVEL_TOO_LOW: verify your identity first.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "WALLET_ALREADY_EXISTS in this currency.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "UNSUPPORTED_CURRENCY.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "KYC_UNAVAILABLE: verification status could not be checked; nothing was created.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<WalletResponse> create(
            @Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId,
            @Valid @RequestBody CreateWalletRequest request) {
        WalletResponse created = walletService.create(require(customerId), request.currency());
        return ResponseEntity.created(URI.create("/api/v1/wallets/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(operationId = "listMyWallets", summary = "List my wallets",
            description = """
                    Every wallet you own, oldest first, including closed ones. **Requires a bearer token.**

                    ### How to test
                    1. Open a wallet, then call this: it appears in the list.
                    2. A new account with no wallet gets an empty list.
                    3. Without a token: **401**.
                    """)
    @ApiResponse(responseCode = "200", description = "Your wallets (possibly none).")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public List<WalletResponse> list(@Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId) {
        return walletService.listFor(require(customerId));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "viewWallet", summary = "View one of my wallets",
            description = """
                    Shows a wallet's balances and status. **Requires a bearer token.**

                    ### How it works
                    * `ledgerBalance` is the recorded funds. `availableBalance` is what you can spend now; it is never more than the ledger balance.
                    * `status` is `ACTIVE`, `FROZEN`, `BLOCKED` or `CLOSED`. Only an `ACTIVE` wallet can send money. A `FROZEN` wallet can still receive money; `BLOCKED` and `CLOSED` wallets cannot.
                    * Another customer's wallet returns **404**, the same as one that does not exist.

                    ### How to test
                    1. Open a wallet and call this with its `id`: **200**.
                    2. Call it with someone else's wallet id, or a random id: **404** `WALLET_NOT_FOUND`.
                    3. Without a token: **401**.
                    """)
    @ApiResponse(responseCode = "200", description = "The wallet.")
    @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND (or it belongs to someone else).",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public WalletResponse view(@Parameter(hidden = true) @RequestHeader(name = CUSTOMER, required = false) UUID customerId,
                               @PathVariable UUID id) {
        return walletService.getFor(require(customerId), id);
    }

    private static UUID require(UUID customerId) {
        if (customerId == null) {
            throw WalletException.unauthenticated();
        }
        return customerId;
    }
}
