package com.flowpay.wallet.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The three balance changes of one transfer. All of them happened, or none did.")
public record WalletTransferResponse(
        String reference,
        @Schema(description = "Taken from the sender: amount + fee.") MovementResponse debit,
        @Schema(description = "Given to the recipient: the amount.") MovementResponse credit,
        @Schema(description = "Given to the platform fee wallet. Absent when the fee is zero.", nullable = true) MovementResponse fee) {
}
