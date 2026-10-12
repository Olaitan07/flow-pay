package com.flowpay.transaction.dto.response;

import com.flowpay.transaction.entity.Funding;
import com.flowpay.transaction.entity.FundingSource;
import com.flowpay.transaction.entity.FundingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A request to add money to a wallet, and how it is going.")
public record FundingResponse(
        @Schema(description = "Unique reference. Quote it when paying and when contacting support.", example = "FND-9F3A91C2B7D04E1A8C55")
        String reference,
        UUID walletId,
        @Schema(example = "5000.00") BigDecimal amount,
        @Schema(example = "NGN") String currency,
        @Schema(example = "BANK_TRANSFER") FundingSource source,
        @Schema(description = "PENDING (waiting for the payment), PROCESSING (payment received, crediting your wallet), "
                + "SUCCESS (your wallet has the money), FAILED (nothing was added), REQUIRES_REVIEW (our team is checking).",
                example = "PENDING") FundingStatus status,
        @Schema(description = "Why it failed, when status is FAILED.", nullable = true) String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public static FundingResponse from(Funding f) {
        return new FundingResponse(f.getReference(), f.getWalletId(), f.getAmount(), f.getCurrency(), f.getSource(),
                f.getStatus(), f.getFailureReason(), f.getCreatedAt(), f.getUpdatedAt());
    }
}
