package com.flowpay.notification.service;

import com.flowpay.notification.dto.request.SendEmailRequest;
import com.flowpay.notification.dto.response.NotificationResponse;

public interface EmailNotificationService {

    NotificationResponse sendEmail(SendEmailRequest request);
}
