package com.flowpay.transaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Audit row for one state change. */
@Entity
@Table(name = "transfer_status_changes")
public class TransferStatusChange {

    @Id
    private UUID id;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 12)
    private TransferStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 12)
    private TransferStatus toStatus;

    @Column(nullable = false, updatable = false, length = 100)
    private String actor;

    @Column(updatable = false, length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransferStatusChange() {
    }

    public TransferStatusChange(UUID transferId, TransferStatus from, TransferStatus to, String actor, String detail, Instant now) {
        this.id = UUID.randomUUID();
        this.transferId = transferId;
        this.fromStatus = from;
        this.toStatus = to;
        this.actor = actor;
        this.detail = detail;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getTransferId() { return transferId; }
    public TransferStatus getFromStatus() { return fromStatus; }
    public TransferStatus getToStatus() { return toStatus; }
    public String getActor() { return actor; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
