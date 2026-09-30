package com.flowpay.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Column(nullable = false)
    private String channel = "EMAIL";

    @Column(nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public Notification(NotificationType type, String recipient, DeliveryStatus status, Instant now) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.recipient = recipient;
        this.status = status;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public NotificationType getType() { return type; }
    public String getRecipient() { return recipient; }
    public DeliveryStatus getStatus() { return status; }
}
