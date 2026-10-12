package com.flowpay.wallet.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "Open a wallet in one currency.")
public record CreateWalletRequest(
        @Schema(description = "ISO 4217 currency code. Always explicit; never assumed. Only supported currencies are accepted.",
                example = "NGN")
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code") String currency) {
}
