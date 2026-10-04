package com.flowpay.kyc.dto.request;

import com.flowpay.kyc.entity.KycOperation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

@Schema(description = "A question to the KYC policy: may this customer do this, for this amount?")
public record AuthorizeOperationRequest(
        @Schema(description = "What the customer is trying to do.", example = "P2P_TRANSFER")
        @NotNull KycOperation operation,
        @Schema(description = "Amount, at most 2 decimal places. Money is never a floating-point number.", example = "10000.00")
        @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @Schema(description = "ISO 4217 currency. Always explicit; never assumed.", example = "NGN")
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code") String currency) {
}
