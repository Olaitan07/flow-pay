package com.flowpay.kyc.dto.response;

import com.flowpay.kyc.entity.KycStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "The result of a review decision.")
public record DecisionResponse(
        UUID submissionId,
        @Schema(example = "VERIFIED") KycStatus status,
        @Schema(description = "The customer's verification level after this decision.", example = "1") int customerLevel) {
}
