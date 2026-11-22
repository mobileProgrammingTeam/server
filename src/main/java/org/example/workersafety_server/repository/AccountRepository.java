package org.example.workersafety_server.repository;

import org.example.workersafety_server.entity.Account;
import org.example.workersafety_server.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") long id);

    Optional<Account> findByAdminLoginIdAndRole(String adminLoginId, Role role);
    boolean existsByRole(Role role);
    boolean existsByRoleAndEnabledTrue(Role role);
    long countByRoleAndEnabledTrue(Role role);
}
