package org.example.workersafety_server.repository;

import org.example.workersafety_server.entity.MobileToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

public interface MobileTokenRepository extends JpaRepository<MobileToken, String> {
    @Modifying
    @Query("update MobileToken t set t.revokedAt = :now where t.account.id = :id and t.revokedAt is null")
    int revokeAll(@Param("id") long id, @Param("now") Instant now);

    @Modifying
    @Query("update MobileToken t set t.revokedAt = :now where t.tokenHash = :hash and t.revokedAt is null")
    int revoke(@Param("hash") String hash, @Param("now") Instant now);
}
