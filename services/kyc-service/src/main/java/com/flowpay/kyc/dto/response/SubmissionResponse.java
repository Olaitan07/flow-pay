package com.flowpay.kyc.dto.response;

import com.flowpay.kyc.entity.KycStatus;
import com.flowpay.kyc.entity.KycSubmission;
import com.flowpay.kyc.service.IdNumberProtector;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A verification request you submitted.")
public record SubmissionResponse(
        @Schema(example = "0b7c2c62-3f2e-4e0a-9d3e-1f6a2f3d9a11") UUID id,
        @Schema(description = "1 or 2.", example = "1") int tier,
        @Schema(description = "Awaiting review shows as PENDING (or REQUIRES_REVIEW for extra checks).", example = "PENDING")
        KycStatus status,
        @Schema(example = "2026-10-04T10:15:30.000Z") Instant submittedAt,
        @Schema(description = "Null until a reviewer decides.") Instant decidedAt,
        @Schema(description = "Why it was rejected. Only present when status is REJECTED.") String rejectionReason,
        @Schema(description = "Tier 2 only: your ID number with all but the last 4 characters hidden.", example = "*******8901")
        String maskedIdNumber) {

    public static SubmissionResponse from(KycSubmission s) {
        return new SubmissionResponse(s.getId(), s.getTier(), s.getStatus(), s.getSubmittedAt(), s.getDecidedAt(),
                s.getStatus() == KycStatus.REJECTED ? s.getDecisionReason() : null,
                s.getIdNumberLast4() == null ? null : IdNumberProtector.mask(s.getIdNumberLast4()));
    }
}
