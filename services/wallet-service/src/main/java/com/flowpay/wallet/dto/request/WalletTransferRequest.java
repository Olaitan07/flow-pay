package com.flowpay.wallet.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Move money from one wallet to another, with a fee, as one all-or-nothing step.")
public record WalletTransferRequest(
        @Schema(description = "Unique reference for the whole transfer. Repeating it with the same details returns the "
                + "original result and moves nothing again. The three movements use it with :DEBIT, :CREDIT and :FEE added.",
                example = "TRF-9F3A91C2B7D04E1A8C55")
        @NotBlank @Size(max = 90) @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "may only contain letters, digits, . _ -")
        String reference,
        @Schema(description = "The wallet money is taken from.") @NotNull UUID senderWalletId,
        @Schema(description = "The wallet that receives the amount.") @NotNull UUID recipientWalletId,
        @Schema(description = "What the recipient receives. More than zero, at most 2 decimal places.", example = "10000.00")
        @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @Schema(description = "The fee, paid to the platform. Zero or more. The sender pays amount + fee.", example = "100.00")
        @NotNull @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal fee,
        @Schema(description = "ISO 4217 currency. Must equal both wallets' currency.", example = "NGN")
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code") String currency) {
}
