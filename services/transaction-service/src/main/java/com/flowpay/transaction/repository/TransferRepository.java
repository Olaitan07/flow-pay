package com.flowpay.transaction.repository;

import com.flowpay.transaction.entity.Transfer;
import com.flowpay.transaction.entity.TransferStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findBySenderCustomerIdAndIdempotencyKey(UUID senderCustomerId, String idempotencyKey);

    /**
     * A transfer the customer may see: one they sent, or one they received that actually completed.
     * Someone else's transfer, or a received one that never completed, looks like one that does not exist.
     */
    @Query("""
            select t from Transfer t where t.reference = :reference
              and (t.senderCustomerId = :customerId or (t.recipientCustomerId = :customerId and t.status = 'SUCCESS'))""")
    Optional<Transfer> findVisible(@Param("reference") String reference, @Param("customerId") UUID customerId);

    @Query("""
            select t from Transfer t
            where t.senderCustomerId = :customerId or (t.recipientCustomerId = :customerId and t.status = 'SUCCESS')
            order by t.createdAt desc""")
    List<Transfer> findVisibleTo(@Param("customerId") UUID customerId, Pageable page);

    /** Transfers recorded but not confirmed (the wallet service was unreachable) for at least as long as the cutoff. */
    @Query("select t from Transfer t where t.status = 'PROCESSING' and t.updatedAt <= :cutoff order by t.updatedAt asc")
    List<Transfer> findStuckProcessing(@Param("cutoff") Instant cutoff, Pageable page);

    /**
     * Moves a transfer to a new status only if it still has the status the caller saw. Returns 1 for exactly one of
     * any number of simultaneous callers.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Transfer t set t.status = :next, t.updatedAt = :now,
                t.failureCode = :failureCode, t.failureReason = :failureReason
            where t.id = :id and t.status = :expected""")
    int transition(@Param("id") UUID id, @Param("expected") TransferStatus expected, @Param("next") TransferStatus next,
                   @Param("now") Instant now, @Param("failureCode") String failureCode,
                   @Param("failureReason") String failureReason);
}
