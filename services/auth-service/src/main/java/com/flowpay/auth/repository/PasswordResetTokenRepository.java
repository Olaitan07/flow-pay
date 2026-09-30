package com.flowpay.auth.repository;

import com.flowpay.auth.entity.PasswordResetToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Uses up a live token. Returns 1 for exactly one caller even if several race with the same link. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update PasswordResetToken t set t.usedAt = :now
            where t.tokenHash = :tokenHash and t.usedAt is null and t.expiresAt > :now""")
    int consumeIfActive(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /** Only the newest emailed link should work, so older ones are cancelled. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :now where t.customerId = :customerId and t.usedAt is null")
    int invalidateOutstanding(@Param("customerId") UUID customerId, @Param("now") Instant now);
}
