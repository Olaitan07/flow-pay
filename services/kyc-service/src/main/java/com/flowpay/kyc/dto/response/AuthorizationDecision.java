package com.flowpay.kyc.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "The policy's answer. If allowed is false, do not perform the operation.")
public record AuthorizationDecision(
        @Schema(example = "false") boolean allowed,
        @Schema(description = "The customer's current verified level.", example = "1") int level,
        @Schema(description = "Why it was refused: KYC_LEVEL_TOO_LOW, KYC_LIMIT_EXCEEDED or UNSUPPORTED_CURRENCY. Null when allowed.",
                example = "KYC_LIMIT_EXCEEDED") String reasonCode,
        @Schema(description = "For KYC_LEVEL_TOO_LOW: the lowest level that allows the operation.", example = "2")
        Integer requiredLevel,
        @Schema(description = "The largest single transaction this level may make.", example = "50000.00")
        BigDecimal maxSingleTransactionAmount,
        @Schema(example = "NGN") String currency) {
}
