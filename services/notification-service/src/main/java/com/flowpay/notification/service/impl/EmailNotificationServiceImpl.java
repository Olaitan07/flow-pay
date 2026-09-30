package com.flowpay.notification.service.impl;

import com.flowpay.notification.dto.request.SendEmailRequest;
import com.flowpay.notification.dto.response.NotificationResponse;
import com.flowpay.notification.entity.DeliveryStatus;
import com.flowpay.notification.entity.Notification;
import com.flowpay.notification.exception.DeliveryFailedException;
import com.flowpay.notification.exception.InvalidNotificationException;
import com.flowpay.notification.repository.NotificationRepository;
import com.flowpay.notification.service.EmailNotificationService;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailNotificationServiceImpl implements EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationServiceImpl.class);

    private final JavaMailSender mailSender;
    private final NotificationRepository notifications;
    private final Clock clock;
    private final String from;

    public EmailNotificationServiceImpl(JavaMailSender mailSender, NotificationRepository notifications, Clock clock,
                                        @Value("${flowpay.notification.from}") String from) {
        this.mailSender = mailSender;
        this.notifications = notifications;
        this.clock = clock;
        this.from = from;
    }

    /**
     * Not transactional on purpose: the delivery record (especially FAILED) must be saved even when we then
     * report the failure to the caller.
     */
    @Override
    public NotificationResponse sendEmail(SendEmailRequest request) {
        Map<String, String> variables = request.variables() == null ? Map.of() : request.variables();
        for (String required : request.type().requiredVariables()) {
            String value = variables.get(required);
            if (value == null || value.isBlank()) {
                throw new InvalidNotificationException("Missing variable '" + required + "' for " + request.type());
            }
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(request.recipient());
        message.setSubject(request.type().subject());
        message.setText(request.type().render(variables));

        try {
            mailSender.send(message);
        } catch (MailException ex) {
            Notification failed = notifications.save(
                    new Notification(request.type(), request.recipient(), DeliveryStatus.FAILED, clock.instant()));
            log.error("Email delivery failed notificationId={} type={} cause={}", failed.getId(), request.type(),
                    ex.getClass().getSimpleName());
            throw new DeliveryFailedException(ex);
        }

        Notification sent = notifications.save(
                new Notification(request.type(), request.recipient(), DeliveryStatus.SENT, clock.instant()));
        log.info("Email sent notificationId={} type={}", sent.getId(), request.type());
        return new NotificationResponse(sent.getId(), sent.getStatus());
    }
}
