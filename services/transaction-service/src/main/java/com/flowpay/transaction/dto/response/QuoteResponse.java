package com.flowpay.transaction.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "What a transfer would cost. Nothing has been moved or recorded.")
public record QuoteResponse(
        @Schema(description = "What the recipient receives.", example = "10000.00") BigDecimal amount,
        @Schema(description = "The fee on top.", example = "100.00") BigDecimal fee,
        @Schema(description = "What leaves your wallet: amount + fee.", example = "10100.00") BigDecimal total,
        @Schema(example = "NGN") String currency) {
}
