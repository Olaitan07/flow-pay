package com.flowpay.wallet.dto.response;

import com.flowpay.wallet.entity.Wallet;
import com.flowpay.wallet.entity.WalletStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A wallet and what it holds.")
public record WalletResponse(
        UUID id,
        UUID customerId,
        @Schema(example = "NGN") String currency,
        @Schema(description = "Recorded funds.", example = "100000.00") BigDecimal ledgerBalance,
        @Schema(description = "What can be spent right now. Never more than the ledger balance.", example = "80000.00")
        BigDecimal availableBalance,
        @Schema(example = "ACTIVE") WalletStatus status,
        Instant createdAt) {

    public static WalletResponse from(Wallet w) {
        return new WalletResponse(w.getId(), w.getCustomerId(), w.getCurrency(), w.getLedgerBalance(),
                w.getAvailableBalance(), w.getStatus(), w.getCreatedAt());
    }
}
