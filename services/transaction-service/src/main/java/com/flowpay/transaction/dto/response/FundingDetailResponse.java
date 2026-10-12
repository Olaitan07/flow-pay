package com.flowpay.transaction.dto.response;

import com.flowpay.transaction.entity.Funding;
import com.flowpay.transaction.entity.FundingSource;
import com.flowpay.transaction.entity.FundingStatus;
import com.flowpay.transaction.entity.FundingStatusChange;
import com.flowpay.transaction.entity.ReviewCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A funding with everything operations need: provider details, review reason and full history. Internal only.")
public record FundingDetailResponse(
        String reference,
        UUID customerId,
        UUID walletId,
        @Schema(example = "5000.00") BigDecimal amount,
        @Schema(example = "NGN") String currency,
        FundingSource source,
        FundingStatus status,
        String providerReference,
        String failureReason,
        @Schema(description = "Set when status is REQUIRES_REVIEW: AMOUNT_MISMATCH or CREDIT_REFUSED.", nullable = true)
        ReviewCode reviewCode,
        String reviewReason,
        Instant createdAt,
        Instant updatedAt,
        @Schema(description = "Every state change, oldest first.") List<Change> history) {

    public record Change(FundingStatus from, FundingStatus to, String actor, String detail, Instant at) {
        public static Change from(FundingStatusChange c) {
            return new Change(c.getFromStatus(), c.getToStatus(), c.getActor(), c.getDetail(), c.getCreatedAt());
        }
    }

    public static FundingDetailResponse of(Funding f, List<FundingStatusChange> history) {
        return new FundingDetailResponse(f.getReference(), f.getCustomerId(), f.getWalletId(), f.getAmount(), f.getCurrency(),
                f.getSource(), f.getStatus(), f.getProviderReference(), f.getFailureReason(), f.getReviewCode(),
                f.getReviewReason(), f.getCreatedAt(), f.getUpdatedAt(), history.stream().map(Change::from).toList());
    }
}
