package org.example.workersafety_server.repository;

import org.example.workersafety_server.entity.Account;
import org.example.workersafety_server.entity.SafetyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

public interface SafetyEventRepository extends JpaRepository<SafetyEvent, Long>, JpaSpecificationExecutor<SafetyEvent> {
    long countByProcessedAtIsNull();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SafetyEvent e set e.processedAt = :now, e.processedBy = :admin, e.adminMemo = :memo where e.id = :id and e.processedAt is null")
    int process(@Param("id") long id, @Param("admin") Account admin,
                @Param("now") Instant now, @Param("memo") String memo);
}
