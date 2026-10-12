package com.flowpay.kyc.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "A reviewer's decision on a submission.")
public record ReviewDecisionRequest(
        @Schema(description = "APPROVE verifies the tier. REJECT turns it down.", example = "APPROVE")
        @NotNull Decision decision,
        @Schema(description = "Required for REJECT (shown to the customer). Optional note for APPROVE.",
                example = "ID number does not match the name on the account.")
        @Size(max = 500) String reason) {

    public enum Decision {
        APPROVE,
        REJECT
    }
}
