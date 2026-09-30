package com.flowpay.notification.dto.response;

import com.flowpay.notification.entity.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Result of a send.")
public record NotificationResponse(
        @Schema(description = "Id of the delivery record.", example = "0b7c2c62-3f2e-4e0a-9d3e-1f6a2f3d9a11") UUID id,
        @Schema(example = "SENT") DeliveryStatus status) {
}
