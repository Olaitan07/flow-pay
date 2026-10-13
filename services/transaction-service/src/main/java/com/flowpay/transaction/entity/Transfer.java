package com.flowpay.transaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One transfer of money from a customer to another. Its status never changes by setting a field: the repository's
 * conditional update moves it only from the status the caller saw, so simultaneous attempts cannot double-process it.
 * The amount, fee and total are fixed at creation and never change.
 */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false, length = 40)
    private String reference;

    @Column(name = "sender_customer_id", nullable = false, updatable = false)
    private UUID senderCustomerId;

    @Column(name = "recipient_customer_id", nullable = false, updatable = false)
    private UUID recipientCustomerId;

    @Column(name = "sender_wallet_id", nullable = false, updatable = false)
    private UUID senderWalletId;

    @Column(name = "recipient_wallet_id", nullable = false, updatable = false)
    private UUID recipientWalletId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal fee;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(updatable = false, length = 140)
    private String narration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private TransferStatus status;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transfer() {
    }

    public Transfer(String reference, UUID senderCustomerId, UUID recipientCustomerId, UUID senderWalletId,
                    UUID recipientWalletId, BigDecimal amount, BigDecimal fee, String currency, String narration,
                    String idempotencyKey, String requestHash, Instant now) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.senderCustomerId = senderCustomerId;
        this.recipientCustomerId = recipientCustomerId;
        this.senderWalletId = senderWalletId;
        this.recipientWalletId = recipientWalletId;
        this.amount = amount;
        this.fee = fee;
        this.total = amount.add(fee);
        this.currency = currency;
        this.narration = narration;
        this.status = TransferStatus.PROCESSING;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public UUID getSenderCustomerId() { return senderCustomerId; }
    public UUID getRecipientCustomerId() { return recipientCustomerId; }
    public UUID getSenderWalletId() { return senderWalletId; }
    public UUID getRecipientWalletId() { return recipientWalletId; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getTotal() { return total; }
    public String getCurrency() { return currency; }
    public String getNarration() { return narration; }
    public TransferStatus getStatus() { return status; }
    public String getFailureCode() { return failureCode; }
    public String getFailureReason() { return failureReason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
