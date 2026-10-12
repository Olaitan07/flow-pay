package com.flowpay.wallet.entity;

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
 * One customer's money in one currency. Balances and status are never changed by setting fields: the repository's
 * conditional updates do it in a single statement, so two simultaneous requests cannot overspend or skip a rule.
 */
@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "ledger_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal ledgerBalance;

    @Column(name = "available_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal availableBalance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private WalletStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Wallet() {
    }

    public Wallet(UUID customerId, String currency, Instant now) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.currency = currency;
        this.ledgerBalance = BigDecimal.ZERO.setScale(2);
        this.availableBalance = BigDecimal.ZERO.setScale(2);
        this.status = WalletStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public String getCurrency() { return currency; }
    public BigDecimal getLedgerBalance() { return ledgerBalance; }
    public BigDecimal getAvailableBalance() { return availableBalance; }
    public WalletStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
