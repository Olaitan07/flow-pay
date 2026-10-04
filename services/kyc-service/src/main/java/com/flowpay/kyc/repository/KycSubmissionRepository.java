package com.flowpay.kyc.repository;

import com.flowpay.kyc.entity.KycStatus;
import com.flowpay.kyc.entity.KycSubmission;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface KycSubmissionRepository extends JpaRepository<KycSubmission, UUID> {

    List<KycSubmission> findByCustomerIdOrderBySubmittedAtDesc(UUID customerId);

    boolean existsByCustomerIdAndTierAndStatusIn(UUID customerId, int tier, Collection<KycStatus> statuses);

    List<KycSubmission> findByStatusInOrderBySubmittedAtAsc(Collection<KycStatus> statuses, Pageable pageable);

    /** True if a different customer already has a live or verified submission with the same ID number hash. */
    @Query("""
            select count(s) > 0 from KycSubmission s
            where s.idNumberHash = :hash and s.customerId <> :customerId and s.status in :statuses""")
    boolean existsOtherCustomerWithIdHash(@Param("hash") String hash, @Param("customerId") UUID customerId,
                                          @Param("statuses") Collection<KycStatus> statuses);

    @Query("select max(s.tier) from KycSubmission s where s.customerId = :customerId and s.status = 'VERIFIED'")
    Integer findHighestVerifiedTier(@Param("customerId") UUID customerId);

    /**
     * Records a decision only if the submission is still waiting for one. Returns 1 for exactly one caller even if
     * several reviewers decide at the same moment.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update KycSubmission s set s.status = :newStatus, s.decidedAt = :now, s.decidedBy = :reviewer,
                s.decisionReason = :reason
            where s.id = :id and s.status in :allowedFrom""")
    int decide(@Param("id") UUID id, @Param("newStatus") KycStatus newStatus, @Param("reviewer") String reviewer,
               @Param("reason") String reason, @Param("now") Instant now,
               @Param("allowedFrom") Collection<KycStatus> allowedFrom);
}
