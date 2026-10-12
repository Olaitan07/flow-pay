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
 * One request to add money to a wallet. Its status never changes by setting a field: the repository's conditional
 * updates move it only from the status the caller saw, so simultaneous confirmations cannot double-process it.
 */
@Entity
@Table(name = "fundings")
public class Funding {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false, length = 40)
    private String reference;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private FundingSource source;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FundingStatus status;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_code", length = 20)
    private ReviewCode reviewCode;

    @Column(name = "review_reason", length = 500)
    private String reviewReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Funding() {
    }

    public Funding(String reference, UUID customerId, UUID walletId, FundingSource source, BigDecimal amount,
                   String currency, Instant now) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.customerId = customerId;
        this.walletId = walletId;
        this.source = source;
        this.amount = amount;
        this.currency = currency;
        this.status = FundingStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public UUID getCustomerId() { return customerId; }
    public UUID getWalletId() { return walletId; }
    public FundingSource getSource() { return source; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public FundingStatus getStatus() { return status; }
    public String getProviderReference() { return providerReference; }
    public String getFailureReason() { return failureReason; }
    public ReviewCode getReviewCode() { return reviewCode; }
    public String getReviewReason() { return reviewReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
