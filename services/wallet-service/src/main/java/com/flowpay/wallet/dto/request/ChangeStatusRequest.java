package com.flowpay.wallet.dto.request;

import com.flowpay.wallet.entity.WalletStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Move a wallet to a new status.")
public record ChangeStatusRequest(
        @Schema(example = "FROZEN") @NotNull WalletStatus status,
        @Schema(description = "Why. Required and kept in the audit trail.", example = "Customer reported a lost phone")
        @NotBlank @Size(max = 500) String reason) {
}
