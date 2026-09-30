package com.flowpay.auth.repository;

import com.flowpay.auth.entity.Credential;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CredentialRepository extends JpaRepository<Credential, UUID> {

    Optional<Credential> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Counts a failed login in a single atomic statement, so many simultaneous guesses cannot slip past the
     * limit. When the limit is reached the account is locked and the counter starts again after the lock.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Credential c set
                c.failedAttempts = case when c.failedAttempts + 1 >= :maxAttempts then 0 else c.failedAttempts + 1 end,
                c.lockedUntil = case when c.failedAttempts + 1 >= :maxAttempts then :lockedUntil else c.lockedUntil end,
                c.updatedAt = :now
            where c.customerId = :customerId""")
    int recordFailedAttempt(@Param("customerId") UUID customerId, @Param("maxAttempts") int maxAttempts,
                            @Param("lockedUntil") Instant lockedUntil, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update Credential c set c.failedAttempts = 0, c.lockedUntil = null, c.updatedAt = :now
            where c.customerId = :customerId and (c.failedAttempts <> 0 or c.lockedUntil is not null)""")
    int resetFailedAttempts(@Param("customerId") UUID customerId, @Param("now") Instant now);
}
