package com.flowpay.transaction.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flowpay.transaction.entity.Transfer;
import com.flowpay.transaction.entity.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A transfer of money between two customers, and how it is going.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferResponse(
        @Schema(description = "Unique reference. Quote it when contacting support.", example = "TRF-9F3A91C2B7D04E1A8C55")
        String reference,
        @Schema(description = "SENT if you sent it, RECEIVED if it was sent to you.", example = "SENT") Direction direction,
        @Schema(description = "The other customer: the recipient if you sent it, the sender if you received it.")
        UUID counterpartyCustomerId,
        @Schema(description = "What the recipient receives.", example = "10000.00") BigDecimal amount,
        @Schema(description = "The fee the sender paid. Only the sender sees it.", example = "100.00", nullable = true) BigDecimal fee,
        @Schema(description = "What left the sender's wallet: amount + fee. Only the sender sees it.", example = "10100.00", nullable = true)
        BigDecimal total,
        @Schema(example = "NGN") String currency,
        @Schema(example = "Rent for October", nullable = true) String narration,
        @Schema(description = "PROCESSING (recorded, money moving or waiting to be confirmed), SUCCESS (the money moved), "
                + "FAILED (nothing was taken from your wallet).", example = "SUCCESS") TransferStatus status,
        @Schema(description = "Why it failed, when status is FAILED. Only the sender sees it.", nullable = true) String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public enum Direction { SENT, RECEIVED }

    /** The transfer as seen by one of its two customers. The recipient never sees the fee or a failure. */
    public static TransferResponse of(Transfer t, UUID viewer) {
        boolean sender = t.getSenderCustomerId().equals(viewer);
        return new TransferResponse(t.getReference(), sender ? Direction.SENT : Direction.RECEIVED,
                sender ? t.getRecipientCustomerId() : t.getSenderCustomerId(), t.getAmount(),
                sender ? t.getFee() : null, sender ? t.getTotal() : null, t.getCurrency(), t.getNarration(), t.getStatus(),
                sender ? t.getFailureReason() : null, t.getCreatedAt(), t.getUpdatedAt());
    }
}
