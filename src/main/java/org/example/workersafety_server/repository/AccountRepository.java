package org.example.workersafety_server.repository;

import org.example.workersafety_server.entity.Account;
import org.example.workersafety_server.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAdminLoginIdAndRole(String adminLoginId, Role role);
    boolean existsByRole(Role role);
    boolean existsByRoleAndEnabledTrue(Role role);
    long countByRoleAndEnabledTrue(Role role);
}
