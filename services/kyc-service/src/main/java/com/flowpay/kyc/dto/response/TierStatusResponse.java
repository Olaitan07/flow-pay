package com.flowpay.kyc.dto.response;

import com.flowpay.kyc.entity.KycStatus;
import com.flowpay.kyc.entity.KycSubmission;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Where one tier stands. NOT_STARTED if you have not submitted it.")
public record TierStatusResponse(
        @Schema(example = "1") int tier,
        @Schema(example = "VERIFIED") KycStatus status,
        @Schema(description = "Your latest submission for this tier, if any.") UUID submissionId,
        Instant submittedAt,
        Instant decidedAt,
        @Schema(description = "Only present when status is REJECTED.") String rejectionReason) {

    public static TierStatusResponse notStarted(int tier) {
        return new TierStatusResponse(tier, KycStatus.NOT_STARTED, null, null, null, null);
    }

    public static TierStatusResponse from(KycSubmission s) {
        return new TierStatusResponse(s.getTier(), s.getStatus(), s.getId(), s.getSubmittedAt(), s.getDecidedAt(),
                s.getStatus() == KycStatus.REJECTED ? s.getDecisionReason() : null);
    }
}
