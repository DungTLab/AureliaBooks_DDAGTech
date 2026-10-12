package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.AuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test suite validating {@link AuditLogRepository} immutability invariants [BR-08-02].
 * Ensures no deletion methods can ever be executed on the audit ledger.
 */
class AuditLogRepositoryTest {

    private final AuditLogRepository auditLogRepository = new AuditLogRepository() {
        @Override
        public AuditLog save(AuditLog auditLog) {
            return auditLog;
        }

        @Override
        public java.util.Optional<AuditLog> findById(Long id) {
            return java.util.Optional.empty();
        }

        @Override
        public org.springframework.data.domain.Page<AuditLog> findAll(org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }

        @Override
        public org.springframework.data.domain.Page<AuditLog> searchLogs(
                String action, String targetTable, Long actorId, java.time.LocalDateTime startDateTime,
                java.time.LocalDateTime endDateTime, String keyword, org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }

        @Override
        public java.util.List<String> findDistinctActions() {
            return java.util.List.of("USER_CREATE", "USER_STATUS_TOGGLE");
        }

        @Override
        public java.util.List<String> findDistinctTargetTables() {
            return java.util.List.of("users", "orders");
        }

        @Override
        public java.util.List<com.ddagtech.aureliabooks.dto.response.ActorOption> findDistinctActors() {
            return java.util.List.of(new com.ddagtech.aureliabooks.dto.response.ActorOption(1L, "Admin User", "admin@aureliabook.vn"));
        }
    };

    @Test
    @DisplayName("delete() must throw UnsupportedOperationException guarding invariant [BR-08-02]")
    void testDelete_ThrowsUnsupportedOperationException() {
        AuditLog entry = new AuditLog();
        assertThatThrownBy(() -> auditLogRepository.delete(entry))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("BẤT BIẾN NHẬT KÝ KIỂM TOÁN [BR-08-02]");
    }

    @Test
    @DisplayName("deleteById() must throw UnsupportedOperationException guarding invariant [BR-08-02]")
    void testDeleteById_ThrowsUnsupportedOperationException() {
        assertThatThrownBy(() -> auditLogRepository.deleteById(1L))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("BẤT BIẾN NHẬT KÝ KIỂM TOÁN [BR-08-02]");
    }

    @Test
    @DisplayName("deleteAll() must throw UnsupportedOperationException guarding invariant [BR-08-02]")
    void testDeleteAll_ThrowsUnsupportedOperationException() {
        assertThatThrownBy(() -> auditLogRepository.deleteAll())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("BẤT BIẾN NHẬT KÝ KIỂM TOÁN [BR-08-02]");
    }

    @Test
    @DisplayName("findDistinctActions() should return mock distinct actions")
    void testFindDistinctActions_ReturnsList() {
        assertThat(auditLogRepository.findDistinctActions())
                .containsExactly("USER_CREATE", "USER_STATUS_TOGGLE");
    }

    @Test
    @DisplayName("findDistinctTargetTables() should return mock distinct tables")
    void testFindDistinctTargetTables_ReturnsList() {
        assertThat(auditLogRepository.findDistinctTargetTables())
                .containsExactly("users", "orders");
    }

    @Test
    @DisplayName("preUpdate() on AuditLog entity must throw UnsupportedOperationException guarding invariant [BR-08-02]")
    void testAuditLogPreUpdate_ThrowsUnsupportedOperationException() {
        AuditLog entry = new AuditLog();
        assertThatThrownBy(entry::preUpdate)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("BẤT BIẾN NHẬT KÝ KIỂM TOÁN [BR-08-02]");
    }

    @Test
    @DisplayName("preRemove() on AuditLog entity must throw UnsupportedOperationException guarding invariant [BR-08-02]")
    void testAuditLogPreRemove_ThrowsUnsupportedOperationException() {
        AuditLog entry = new AuditLog();
        assertThatThrownBy(entry::preRemove)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("BẤT BIẾN NHẬT KÝ KIỂM TOÁN [BR-08-02]");
    }
}
