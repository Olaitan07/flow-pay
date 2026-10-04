package com.flowpay.kyc.repository;

import com.flowpay.kyc.entity.KycDecision;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KycDecisionRepository extends JpaRepository<KycDecision, UUID> {

    List<KycDecision> findBySubmissionIdOrderByCreatedAtAsc(UUID submissionId);
}
