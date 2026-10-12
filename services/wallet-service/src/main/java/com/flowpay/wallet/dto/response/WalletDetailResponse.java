package com.flowpay.wallet.dto.response;

import com.flowpay.wallet.entity.WalletStatus;
import com.flowpay.wallet.entity.WalletStatusChange;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "A wallet with its status history. Internal only.")
public record WalletDetailResponse(
        WalletResponse wallet,
        @Schema(description = "Every status change, oldest first.") List<StatusChange> statusHistory) {

    public record StatusChange(@Schema(example = "ACTIVE") WalletStatus from, @Schema(example = "FROZEN") WalletStatus to,
                               String reason, String actor, Instant at) {
        public static StatusChange from(WalletStatusChange c) {
            return new StatusChange(c.getFromStatus(), c.getToStatus(), c.getReason(), c.getActor(), c.getCreatedAt());
        }
    }
}
