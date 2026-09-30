package com.flowpay.notification.controller;

import com.flowpay.notification.dto.request.SendEmailRequest;
import com.flowpay.notification.dto.response.NotificationResponse;
import com.flowpay.notification.service.EmailNotificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Called by other FlowPay services. Protected by the internal API key; not exposed by the gateway. */
@RestController
@RequestMapping("/internal/notifications")
public class InternalNotificationController {

    private final EmailNotificationService emailNotificationService;

    public InternalNotificationController(EmailNotificationService emailNotificationService) {
        this.emailNotificationService = emailNotificationService;
    }

    @PostMapping("/email")
    public ResponseEntity<NotificationResponse> sendEmail(@Valid @RequestBody SendEmailRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(emailNotificationService.sendEmail(request));
    }
}
