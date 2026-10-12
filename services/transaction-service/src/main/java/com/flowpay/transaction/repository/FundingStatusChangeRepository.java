package com.flowpay.transaction.repository;

import com.flowpay.transaction.entity.FundingStatusChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FundingStatusChangeRepository extends JpaRepository<FundingStatusChange, UUID> {

    List<FundingStatusChange> findByFundingIdOrderByCreatedAtAsc(UUID fundingId);
}
