package com.flowpay.wallet.controller;

import com.flowpay.wallet.dto.request.ChangeStatusRequest;
import com.flowpay.wallet.dto.request.MovementRequest;
import com.flowpay.wallet.dto.response.ErrorResponse;
import com.flowpay.wallet.dto.response.MovementResponse;
import com.flowpay.wallet.dto.response.WalletDetailResponse;
import com.flowpay.wallet.service.WalletMovementService;
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

    public InternalWalletController(WalletStatusService statusService, WalletMovementService movementService) {
        this.statusService = statusService;
        this.movementService = movementService;
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
