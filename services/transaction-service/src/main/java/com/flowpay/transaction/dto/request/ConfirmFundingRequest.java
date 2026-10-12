package com.flowpay.transaction.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "What the payment provider reports about a funding.")
public record ConfirmFundingRequest(
        @Schema(example = "SUCCESS") @NotNull Outcome outcome,
        @Schema(description = "The provider's own reference for this payment.", example = "PSK_8f3a91c2")
        @Size(max = 100) String providerReference,
        @Schema(description = "What the provider actually received. Required when the outcome is SUCCESS; it must equal the requested amount.",
                example = "5000.00")
        @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @Schema(description = "Currency the provider received. Required when the outcome is SUCCESS.", example = "NGN")
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code") String currency,
        @Schema(description = "Why the payment failed. Used when the outcome is FAILED.", example = "Insufficient funds at the bank")
        @Size(max = 500) String failureReason) {

    public enum Outcome {
        SUCCESS, FAILED
    }
}
