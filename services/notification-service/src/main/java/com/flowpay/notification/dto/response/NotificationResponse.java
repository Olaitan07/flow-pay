package com.flowpay.notification.dto.response;

import com.flowpay.notification.entity.DeliveryStatus;
import java.util.UUID;

public record NotificationResponse(UUID id, DeliveryStatus status) {
}
