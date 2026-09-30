package com.flowpay.auth.repository;

import com.flowpay.auth.entity.OtpChallenge;
import com.flowpay.auth.entity.OtpPurpose;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update OtpChallenge c set c.attempts = c.attempts + 1 where c.id = :id and c.consumedAt is null")
    int recordFailedAttempt(@Param("id") UUID id);

    /** Uses up a live challenge. Fails if it is expired, already used, or out of attempts. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update OtpChallenge c set c.consumedAt = :now
            where c.id = :id and c.consumedAt is null and c.expiresAt > :now and c.attempts < :maxAttempts""")
    int consumeIfActive(@Param("id") UUID id, @Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    /** Only the newest code for a purpose should work. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update OtpChallenge c set c.consumedAt = :now
            where c.customerId = :customerId and c.purpose = :purpose and c.consumedAt is null""")
    int invalidateOutstanding(@Param("customerId") UUID customerId, @Param("purpose") OtpPurpose purpose,
                              @Param("now") Instant now);
}
