package com.vedant.hisaab.repository;

import com.vedant.hisaab.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Called before issuing a new link, so a user only ever has one live link. */
    void deleteByUserId(Long userId);

    /** Housekeeping: drop links that expired a while ago. */
    void deleteByExpiresAtBefore(Instant cutoff);
}
