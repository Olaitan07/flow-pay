package com.flowpay.wallet.controller;

import com.flowpay.wallet.dto.request.ChangeStatusRequest;
import com.flowpay.wallet.dto.request.MovementRequest;
import com.flowpay.wallet.dto.request.WalletTransferRequest;
import com.flowpay.wallet.dto.response.ErrorResponse;
import com.flowpay.wallet.dto.response.MovementResponse;
import com.flowpay.wallet.dto.response.WalletTransferResponse;
import com.flowpay.wallet.dto.response.WalletResponse;
import com.flowpay.wallet.dto.response.WalletDetailResponse;
import com.flowpay.wallet.service.WalletMovementService;
import com.flowpay.wallet.service.WalletService;
import com.flowpay.wallet.service.WalletTransferService;
import com.flowpay.wallet.service.WalletStatusService;
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
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff and service-to-service endpoints. Never routed by the gateway; guarded by the internal API key. */
@RestController
@RequestMapping("/internal/wallets")
@Validated
@SecurityRequirement(name = "internalApiKey")
@Tag(name = "Internal: wallets",
        description = "Not reachable through the gateway. Needs the X-Internal-Api-Key header. Used by operations staff and by other FlowPay services.")
public class InternalWalletController {

    private static final String ACTOR = "X-Actor";

    private final WalletStatusService statusService;
    private final WalletMovementService movementService;
    private final WalletTransferService transferService;
    private final WalletService walletService;

    public InternalWalletController(WalletStatusService statusService, WalletMovementService movementService,
                                    WalletTransferService transferService, WalletService walletService) {
        this.statusService = statusService;
        this.movementService = movementService;
        this.transferService = transferService;
        this.walletService = walletService;
    }

    @GetMapping("/by-customer/{customerId}")
    @Operation(operationId = "findWalletByCustomer", summary = "Find a customer's wallet in a currency",
            description = """
                    Returns the customer's live (not closed) wallet in one currency. **Internal only.** Transfers use it to find the recipient's wallet from their customer id.

                    ### How to test (direct call, development only)
                    1. Open a wallet for a customer, then call this with their `customerId` and `currency=NGN`: **200** with the wallet.
                    2. A customer with no wallet in that currency, or a closed one: **404** `WALLET_NOT_FOUND`.
                    """)
    @ApiResponse(responseCode = "200", description = "The wallet.")
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public WalletResponse byCustomer(@PathVariable UUID customerId,
                                     @Parameter(description = "ISO 4217 currency.", example = "NGN") @RequestParam String currency) {
        return walletService.liveWalletOf(customerId, currency);
    }

    @PostMapping("/transfer")
    @Operation(operationId = "transferBetweenWallets", summary = "Move money between two wallets, with a fee, atomically",
            description = """
                    Takes `amount + fee` from the sender, gives `amount` to the recipient and `fee` to the platform fee wallet, as **one all-or-nothing step**. **Internal only.** Customers never call it; the transaction service does after it has checked the customer, KYC and fee.

                    ### How it works
                    * All three changes happen in one database transaction. If any fails (not enough money, wallet not active, recipient cannot receive) **none** happen.
                    * The sender wallet must be `ACTIVE` with enough **available** balance for `amount + fee`. The recipient may be `ACTIVE` or `FROZEN`. Both wallets must hold the request's currency.
                    * `reference` makes a retry safe: the same reference with the same details returns the first result (**200**) and moves nothing again; different details are **409** `REFERENCE_REUSED`.
                    * The fee wallet is created on first use. A fee of `0` skips it.

                    ### How to test (direct call, development only)
                    1. Credit wallet A with `50000.00`. Transfer `10000.00` with fee `100.00` to wallet B: **201**. A has `39900.00`, B `10000.00`, the fee wallet `100.00`.
                    2. Repeat exactly: **200**, balances unchanged.
                    3. Transfer more than A holds: **422** `INSUFFICIENT_FUNDS`, and nobody's balance changed. Exactly `amount` with no room for the fee fails the same way.
                    4. Freeze A: **422** `WALLET_NOT_ACTIVE`. Block B: **422** `RECIPIENT_NOT_ACCEPTING_FUNDS`, and A is **not** debited.
                    5. Same wallet for both: **422** `SAME_WALLET`. Amount `0`, negative or with more than 2 decimals: **400**.
                    """)
    @ApiResponse(responseCode = "201", description = "Transfer done.")
    @ApiResponse(responseCode = "200", description = "A repeat of an earlier identical request; nothing moved again.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "SENDER_WALLET_NOT_FOUND or RECIPIENT_WALLET_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "REFERENCE_REUSED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_FUNDS, WALLET_NOT_ACTIVE, RECIPIENT_NOT_ACCEPTING_FUNDS, CURRENCY_MISMATCH or SAME_WALLET.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "FEE_WALLET_UNAVAILABLE: nothing moved; safe to retry with the same reference.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<WalletTransferResponse> transfer(@Valid @RequestBody WalletTransferRequest request) {
        var result = transferService.transfer(request);
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(result.transfer());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getWalletForOperations", summary = "Open a wallet with its status history",
            description = """
                    Shows any wallet with every status change, oldest first. **Internal only.**

                    ### How to test (direct call, development only)
                    1. Call with `X-Internal-Api-Key` and a wallet id: **200**.
                    2. Unknown id: **404**. Missing key: **401**.
                    """)
    @ApiResponse(responseCode = "200", description = "The wallet and its history.")
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public WalletDetailResponse detail(@PathVariable UUID id) {
        return statusService.detail(id);
    }

    @PostMapping("/{id}/status")
    @Operation(operationId = "changeWalletStatus", summary = "Freeze, block, reactivate or close a wallet",
            description = """
                    Moves a wallet to a new status. **Internal only.** Needs `X-Actor` (who is doing this) and a reason; both are kept in the audit trail.

                    ### The status rules
                    | Status | Can send money | Can receive money |
                    |---|---|---|
                    | `ACTIVE` | yes | yes |
                    | `FROZEN` | no | yes |
                    | `BLOCKED` | no | no |
                    | `CLOSED` | no | no (final, cannot be reopened) |

                    Allowed changes: `ACTIVE`, `FROZEN` and `BLOCKED` can each become either of the other two, and any of them can become `CLOSED`. A `CLOSED` wallet cannot change. **Closing needs a balance of exactly zero**, checked at the moment of closing so a deposit arriving at the same time is never lost.

                    ### How to test (direct call, development only)
                    1. Freeze an active wallet with `{"status":"FROZEN","reason":"Customer request"}` and `X-Actor: ops.jane`: **200**, history shows the change.
                    2. Debit it (see the debit endpoint): **422** `WALLET_NOT_ACTIVE`. Credit it: allowed.
                    3. Set the same status again: **422** `WALLET_STATUS_UNCHANGED`. Change a closed wallet: **422** `INVALID_STATUS_TRANSITION`.
                    4. Close a wallet that holds money: **422** `WALLET_NOT_EMPTY`.
                    5. Omit `X-Actor` or the reason: **400**.
                    """)
    @ApiResponse(responseCode = "200", description = "The wallet after the change, with its history.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: missing actor, status or reason.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "WALLET_STATUS_CHANGED: someone else changed it at the same moment.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "WALLET_STATUS_UNCHANGED, INVALID_STATUS_TRANSITION or WALLET_NOT_EMPTY.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public WalletDetailResponse changeStatus(
            @PathVariable UUID id,
            @Parameter(description = "Who is making the change. Recorded in the audit trail.", example = "ops.jane")
            @RequestHeader(ACTOR) @NotBlank @Size(max = 100) String actor,
            @Valid @RequestBody ChangeStatusRequest request) {
        return statusService.change(id, request, actor.trim());
    }

    @PostMapping("/{id}/credit")
    @Operation(operationId = "creditWallet", summary = "Put money into a wallet",
            description = """
                    Adds money to a wallet. **Internal only.** This is the building block that wallet funding and transfers use; customers never call it.

                    ### How it works
                    * The wallet must be `ACTIVE` or `FROZEN`, and the currency must match the wallet's.
                    * `reference` must be unique. Sending the **same reference with the same details** again returns the original result with **200** and adds nothing; a different movement with a used reference is **409** `REFERENCE_REUSED`.
                    * Both the ledger and the available balance go up by the amount.

                    ### How to test (direct call, development only)
                    1. Credit `5000.00` NGN with reference `test-1`: **201**, balances `5000.00`.
                    2. Repeat exactly: **200**, same body, balance still `5000.00`.
                    3. Reuse `test-1` with `6000.00`: **409** `REFERENCE_REUSED`.
                    4. Credit a `BLOCKED` or `CLOSED` wallet: **422** `WALLET_NOT_ACCEPTING_FUNDS`. Wrong currency: **422** `CURRENCY_MISMATCH`.
                    5. Amount `0`, negative, or more than 2 decimals: **400**.
                    """)
    @ApiResponse(responseCode = "201", description = "Money added.")
    @ApiResponse(responseCode = "200", description = "A repeat of an earlier identical request; nothing was added again.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "REFERENCE_REUSED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "WALLET_NOT_ACCEPTING_FUNDS or CURRENCY_MISMATCH.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<MovementResponse> credit(@PathVariable UUID id, @Valid @RequestBody MovementRequest request) {
        return respond(movementService.credit(id, request));
    }

    @PostMapping("/{id}/debit")
    @Operation(operationId = "debitWallet", summary = "Take money out of a wallet",
            description = """
                    Removes money from a wallet. **Internal only.** The building block for transfers and withdrawals; customers never call it.

                    ### How it works
                    * The wallet must be `ACTIVE`, in the same currency, with enough **available** balance. A `FROZEN`, `BLOCKED` or `CLOSED` wallet cannot send money.
                    * The balance can never go negative: the check and the change are a single database statement, so two simultaneous debits cannot spend the same money.
                    * `reference` works as for credit: a repeat is safe and moves nothing twice.

                    ### How to test (direct call, development only)
                    1. Credit `5000.00`, then debit `2000.00` with a new reference: **201**, balances `3000.00`.
                    2. Debit `3000.01`: **422** `INSUFFICIENT_FUNDS`, balance unchanged.
                    3. Freeze the wallet and debit: **422** `WALLET_NOT_ACTIVE`.
                    4. Repeat a debit reference: **200**, balance not reduced again.
                    """)
    @ApiResponse(responseCode = "201", description = "Money removed.")
    @ApiResponse(responseCode = "200", description = "A repeat of an earlier identical request; nothing was removed again.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Internal-Api-Key.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "REFERENCE_REUSED.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "WALLET_NOT_ACTIVE, INSUFFICIENT_FUNDS or CURRENCY_MISMATCH.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<MovementResponse> debit(@PathVariable UUID id, @Valid @RequestBody MovementRequest request) {
        return respond(movementService.debit(id, request));
    }

    private static ResponseEntity<MovementResponse> respond(WalletMovementService.Result result) {
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(result.movement());
    }
}
