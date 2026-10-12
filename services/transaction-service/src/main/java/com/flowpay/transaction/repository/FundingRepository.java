package com.flowpay.transaction.repository;

import com.flowpay.transaction.entity.Funding;
import com.flowpay.transaction.entity.FundingStatus;
import com.flowpay.transaction.entity.ReviewCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FundingRepository extends JpaRepository<Funding, UUID> {

    Optional<Funding> findByReference(String reference);

    Optional<Funding> findByReferenceAndCustomerId(String reference, UUID customerId);

    List<Funding> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable page);

    /** Fundings stuck mid-way (payment confirmed, wallet not yet credited) for at least as long as the cutoff. */
    @Query("select f from Funding f where f.status = 'PROCESSING' and f.updatedAt <= :cutoff order by f.updatedAt asc")
    List<Funding> findStuckProcessing(@Param("cutoff") Instant cutoff, Pageable page);

    /**
     * Moves a funding to a new status only if it still has the status the caller saw. Returns 1 for exactly one of
     * any number of simultaneous callers. Clears review details unless the new status is a review.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Funding f set f.status = :next, f.updatedAt = :now,
                f.providerReference = coalesce(:providerReference, f.providerReference),
                f.failureReason = coalesce(:failureReason, f.failureReason),
                f.reviewCode = :reviewCode, f.reviewReason = :reviewReason
            where f.id = :id and f.status = :expected""")
    int transition(@Param("id") UUID id, @Param("expected") FundingStatus expected, @Param("next") FundingStatus next,
                   @Param("now") Instant now, @Param("providerReference") String providerReference,
                   @Param("failureReason") String failureReason, @Param("reviewCode") ReviewCode reviewCode,
                   @Param("reviewReason") String reviewReason);
}
