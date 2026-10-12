package com.flowpay.transaction.dto.request;

import com.flowpay.transaction.entity.FundingSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Ask to add money to one of your wallets.")
public record InitiateFundingRequest(
        @Schema(description = "The wallet to fund. It must be yours.", example = "9636018a-2f90-46a7-add3-2549b9841b5f")
        @NotNull UUID walletId,
        @Schema(description = "Amount, more than zero, at most 2 decimal places. Money is never a floating-point number.",
                example = "5000.00")
        @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @Schema(description = "ISO 4217 currency. Must equal the wallet's currency; never assumed.", example = "NGN")
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code") String currency,
        @Schema(description = "Where the money comes from.", example = "BANK_TRANSFER")
        @NotNull FundingSource source) {
}
