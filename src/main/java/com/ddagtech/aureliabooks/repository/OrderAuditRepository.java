package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.AuditLog;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;
import java.util.List;

/** Read-only access to the existing ledger for order history and refund confirmations. */
public interface OrderAuditRepository extends Repository<AuditLog, Long> {
    List<AuditLog> findByTargetTableAndTargetIdAndActionInOrderByCreatedAtAscIdAsc(
            String targetTable, Long targetId, List<String> actions);

    @EntityGraph(attributePaths = "user")
    List<AuditLog> findByTargetTableAndTargetIdOrderByCreatedAtAscIdAsc(String targetTable, Long targetId);
}
