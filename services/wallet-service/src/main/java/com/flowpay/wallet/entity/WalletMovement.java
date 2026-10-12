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

/** A balance change. Written once and never modified. The unique reference makes a retry harmless. */
@Entity
@Table(name = "wallet_movements")
public class WalletMovement {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false, length = 100)
    private String reference;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 6)
    private MovementType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "ledger_balance_after", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal ledgerBalanceAfter;

    @Column(name = "available_balance_after", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal availableBalanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WalletMovement() {
    }

    public WalletMovement(String reference, UUID walletId, MovementType type, BigDecimal amount, String currency,
                          BigDecimal ledgerBalanceAfter, BigDecimal availableBalanceAfter, Instant now) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.walletId = walletId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.ledgerBalanceAfter = ledgerBalanceAfter;
        this.availableBalanceAfter = availableBalanceAfter;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public UUID getWalletId() { return walletId; }
    public MovementType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BigDecimal getLedgerBalanceAfter() { return ledgerBalanceAfter; }
    public BigDecimal getAvailableBalanceAfter() { return availableBalanceAfter; }
    public Instant getCreatedAt() { return createdAt; }
}
