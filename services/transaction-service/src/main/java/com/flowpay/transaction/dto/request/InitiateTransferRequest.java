package com.flowpay.transaction.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Send money to another FlowPay customer. You are the sender; it is taken from your token, never from here.")
public record InitiateTransferRequest(
        @Schema(description = "The customer who receives the money.", example = "5a3d8b0e-6f0c-4f0a-9a43-0d6a8a3c1b11")
        @NotNull UUID recipientCustomerId,
        @Schema(description = "What the recipient receives. More than zero, at most 2 decimal places. The fee is added on top, "
                + "so you pay amount + fee. Money is never a floating-point number.", example = "10000.00")
        @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @Schema(description = "ISO 4217 currency. Must be the currency of both wallets; never assumed.", example = "NGN")
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code") String currency,
        @Schema(description = "An optional note the recipient sees, up to 140 characters.", example = "Rent for October", nullable = true)
        @Size(max = 140) String narration) {
}
