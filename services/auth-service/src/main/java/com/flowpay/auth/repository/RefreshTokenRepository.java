package com.flowpay.auth.repository;

import com.flowpay.auth.entity.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Marks a live token as used. Returns 1 for exactly one caller even if several race with the same token. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now
            where t.tokenHash = :tokenHash and t.revokedAt is null and t.expiresAt > :now""")
    int consumeIfActive(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.customerId = :customerId and t.revokedAt is null")
    int revokeAllForCustomer(@Param("customerId") UUID customerId, @Param("now") Instant now);
}
