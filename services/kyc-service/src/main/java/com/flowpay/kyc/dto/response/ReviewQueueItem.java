package com.flowpay.kyc.dto.response;

import com.flowpay.kyc.entity.IdType;
import com.flowpay.kyc.entity.KycStatus;
import com.flowpay.kyc.entity.KycSubmission;
import com.flowpay.kyc.service.IdNumberProtector;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A submission waiting for a decision. Sensitive data is masked in the list.")
public record ReviewQueueItem(
        UUID id,
        UUID customerId,
        @Schema(example = "2") int tier,
        @Schema(description = "REQUIRES_REVIEW means an automatic check flagged it (for example the ID was used by another customer).")
        KycStatus status,
        Instant submittedAt,
        IdType idType,
        @Schema(example = "*******8901") String maskedIdNumber) {

    public static ReviewQueueItem from(KycSubmission s) {
        return new ReviewQueueItem(s.getId(), s.getCustomerId(), s.getTier(), s.getStatus(), s.getSubmittedAt(),
                s.getIdType(), s.getIdNumberLast4() == null ? null : IdNumberProtector.mask(s.getIdNumberLast4()));
    }
}
