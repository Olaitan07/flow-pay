package com.flowpay.kyc.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Your verification status, per tier, and what your current level allows.")
public record KycStatusResponse(
        @Schema(example = "6fbf8203-6ee0-4b39-aa85-d721d712480c") UUID customerId,
        @Schema(description = "Highest tier that is VERIFIED (0 if none).", example = "1") int level,
        List<TierStatusResponse> tiers,
        CapabilitiesResponse capabilities) {
}
