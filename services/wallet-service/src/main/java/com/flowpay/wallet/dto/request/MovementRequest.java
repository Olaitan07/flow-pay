package com.flowpay.wallet.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "Put money into, or take money out of, a wallet.")
public record MovementRequest(
        @Schema(description = "Unique reference for this movement. Sending the same reference again with the same details "
                + "returns the original result and moves no money a second time.", example = "fund-2026-10-12-0001")
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9._:-]+$", message = "may only contain letters, digits, . _ : -")
        String reference,
        @Schema(description = "Amount, more than zero, at most 2 decimal places. Money is never a floating-point number.",
                example = "5000.00")
        @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @Schema(description = "ISO 4217 currency. Must equal the wallet's currency.", example = "NGN")
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code") String currency) {
}
