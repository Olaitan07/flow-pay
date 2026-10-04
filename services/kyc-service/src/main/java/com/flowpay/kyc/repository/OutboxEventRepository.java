package com.flowpay.kyc.repository;

import com.flowpay.kyc.entity.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** SKIP LOCKED lets several service instances publish different events instead of fighting over the same ones. */
    @Query(value = """
            select * from outbox_events where published_at is null
            order by created_at limit :batchSize for update skip locked""", nativeQuery = true)
    List<OutboxEvent> lockUnpublished(@Param("batchSize") int batchSize);
}
