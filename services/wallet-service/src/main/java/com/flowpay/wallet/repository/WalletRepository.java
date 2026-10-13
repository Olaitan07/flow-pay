package com.flowpay.wallet.repository;

import com.flowpay.wallet.entity.Wallet;
import com.flowpay.wallet.entity.WalletStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    List<Wallet> findByCustomerIdOrderByCreatedAtAsc(UUID customerId);

    Optional<Wallet> findByIdAndCustomerId(UUID id, UUID customerId);

    /** The customer's live (not closed) wallet in a currency. There is at most one. */
    Optional<Wallet> findFirstByCustomerIdAndCurrencyAndStatusNot(UUID customerId, String currency, WalletStatus excluded);

    /**
     * Creates the platform fee wallet if it does not exist yet. Safe to call from any number of requests at once:
     * the unique index lets exactly one insert win and the others do nothing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into wallets (id, customer_id, currency, ledger_balance, available_balance, status, created_at, updated_at)
            values (:id, :customerId, :currency, 0, 0, 'ACTIVE', :now, :now)
            on conflict (customer_id, currency) where status <> 'CLOSED' do nothing""", nativeQuery = true)
    int createIfAbsent(@Param("id") UUID id, @Param("customerId") UUID customerId, @Param("currency") String currency,
                       @Param("now") Instant now);

    @Query("select count(w) > 0 from Wallet w where w.customerId = :customerId and w.currency = :currency and w.status <> 'CLOSED'")
    boolean existsLive(@Param("customerId") UUID customerId, @Param("currency") String currency);

    /**
     * Takes money out in one statement that also checks every rule: the wallet must be ACTIVE, in this currency, and
     * hold enough available funds. Returns 1 if it happened, 0 if any rule failed. Because the check and the change
     * are the same statement, two simultaneous debits can never spend the same money.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Wallet w set w.ledgerBalance = w.ledgerBalance - :amount,
                w.availableBalance = w.availableBalance - :amount, w.updatedAt = :now
            where w.id = :id and w.status = 'ACTIVE' and w.currency = :currency and w.availableBalance >= :amount""")
    int debit(@Param("id") UUID id, @Param("amount") BigDecimal amount, @Param("currency") String currency,
              @Param("now") Instant now);

    /** Puts money in. The wallet must be ACTIVE or FROZEN and in this currency. Returns 1 or 0. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Wallet w set w.ledgerBalance = w.ledgerBalance + :amount,
                w.availableBalance = w.availableBalance + :amount, w.updatedAt = :now
            where w.id = :id and w.status in ('ACTIVE', 'FROZEN') and w.currency = :currency""")
    int credit(@Param("id") UUID id, @Param("amount") BigDecimal amount, @Param("currency") String currency,
               @Param("now") Instant now);

    /** Moves a wallet to a new status only if it still has the status the caller saw. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Wallet w set w.status = :next, w.updatedAt = :now where w.id = :id and w.status = :expected")
    int changeStatus(@Param("id") UUID id, @Param("expected") WalletStatus expected, @Param("next") WalletStatus next,
                     @Param("now") Instant now);

    /** Closes a wallet only if it is empty at this very moment, so a deposit arriving at the same time cannot be lost. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Wallet w set w.status = 'CLOSED', w.updatedAt = :now
            where w.id = :id and w.status = :expected and w.ledgerBalance = 0 and w.availableBalance = 0""")
    int closeIfEmpty(@Param("id") UUID id, @Param("expected") WalletStatus expected, @Param("now") Instant now);
}
