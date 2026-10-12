package com.flowpay.wallet.dto.response;

import com.flowpay.wallet.entity.MovementType;
import com.flowpay.wallet.entity.WalletMovement;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "The result of a credit or debit.")
public record MovementResponse(
        String reference,
        UUID walletId,
        @Schema(example = "DEBIT") MovementType type,
        @Schema(example = "5000.00") BigDecimal amount,
        @Schema(example = "NGN") String currency,
        @Schema(description = "Ledger balance right after this movement.", example = "95000.00") BigDecimal ledgerBalanceAfter,
        @Schema(description = "Available balance right after this movement.", example = "75000.00") BigDecimal availableBalanceAfter,
        Instant createdAt) {

    public static MovementResponse from(WalletMovement m) {
        return new MovementResponse(m.getReference(), m.getWalletId(), m.getType(), m.getAmount(), m.getCurrency(),
                m.getLedgerBalanceAfter(), m.getAvailableBalanceAfter(), m.getCreatedAt());
    }
}
