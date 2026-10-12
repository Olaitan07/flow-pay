package com.flowpay.kyc.dto.response;

import com.flowpay.kyc.entity.KycOperation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

@Schema(description = "What a verification level allows.")
public record CapabilitiesResponse(
        @Schema(description = "0 = registered only, 1 = basic, 2 = full.", example = "1") int level,
        @Schema(description = "Operations this level may perform.", example = "[\"FUND_WALLET\", \"P2P_TRANSFER\"]")
        List<KycOperation> allowedOperations,
        @Schema(description = "Largest single transaction this level may make.", example = "50000.00")
        BigDecimal maxSingleTransactionAmount,
        @Schema(example = "NGN") String currency) {
}
