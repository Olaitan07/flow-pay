package com.flowpay.kyc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One line of the audit trail. Rows are only ever added, never changed. */
@Entity
@Table(name = "kyc_decisions")
public class KycDecision {

    @Id
    private UUID id;

    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DecisionType type;

    @Column(nullable = false, updatable = false)
    private String actor;

    @Column(updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KycDecision() {
    }

    public KycDecision(UUID submissionId, UUID customerId, DecisionType type, String actor, String reason, Instant now) {
        this.id = UUID.randomUUID();
        this.submissionId = submissionId;
        this.customerId = customerId;
        this.type = type;
        this.actor = actor;
        this.reason = reason;
        this.createdAt = now;
    }

    public DecisionType getType() { return type; }
    public String getActor() { return actor; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
