package com.flowpay.wallet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Audit row: who moved a wallet from one status to another, and why. */
@Entity
@Table(name = "wallet_status_changes")
public class WalletStatusChange {

    @Id
    private UUID id;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, updatable = false, length = 10)
    private WalletStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 10)
    private WalletStatus toStatus;

    @Column(nullable = false, updatable = false, length = 500)
    private String reason;

    @Column(nullable = false, updatable = false, length = 100)
    private String actor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WalletStatusChange() {
    }

    public WalletStatusChange(UUID walletId, WalletStatus from, WalletStatus to, String reason, String actor, Instant now) {
        this.id = UUID.randomUUID();
        this.walletId = walletId;
        this.fromStatus = from;
        this.toStatus = to;
        this.reason = reason;
        this.actor = actor;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getWalletId() { return walletId; }
    public WalletStatus getFromStatus() { return fromStatus; }
    public WalletStatus getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public String getActor() { return actor; }
    public Instant getCreatedAt() { return createdAt; }
}
