package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.BootstrapAdminRequest;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@EnabledIfSystemProperty(named = "bootstrap.mysql", matches = "true")
class AdminBootstrapMySqlTest {

    @Autowired AdminBootstrapService bootstrap;
    @Autowired JdbcTemplate jdbc;

    @Test
    void firstAdminPreservationConflictsAndConcurrentStartup() throws Exception {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertThat(database).startsWith("auth_bootstrap_qa_");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isZero();
        jdbc.update("INSERT INTO roles(id,role_name) VALUES (71,'ROLE_CUSTOMER'),(99,'ROLE_ADMIN')");
        var request = new BootstrapAdminRequest("owner@example.test", "Bootstrap391@", "0912345678");
        assertThat(bootstrap.initialize(request)).isTrue();
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, request.email());
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, id);
        assertThat(new BCryptPasswordEncoder().matches(request.password(), hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT role_id FROM users WHERE id=?", Long.class, id)).isEqualTo(99L);
        jdbc.update("UPDATE users SET is_active=false, full_name='Keep this name' WHERE id=?", id);
        assertThat(bootstrap.initialize(new BootstrapAdminRequest("", "", null))).isFalse();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, id)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT is_active FROM users WHERE id=?", Boolean.class, id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE id=?", String.class, id)).isEqualTo("Keep this name");
        // QA-only demotion creates the no-admin state for collision tests; production bootstrap never does this.
        jdbc.update("UPDATE users SET role_id=71 WHERE id=?", id);
        assertThatThrownBy(() -> bootstrap.initialize(request)).isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicts");
        assertThatThrownBy(() -> bootstrap.initialize(new BootstrapAdminRequest("other@example.test", "Bootstrap391@", request.phone())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicts");
        assertThat(jdbc.queryForObject("SELECT role_id FROM users WHERE id=?", Long.class, id)).isEqualTo(71L);
        var concurrent = new BootstrapAdminRequest("new-owner@example.test", "Bootstrap391@", null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> create = () -> bootstrap.initialize(concurrent);
            var first = pool.submit(create);
            var second = pool.submit(create);
            assertThat(first.get(20, TimeUnit.SECONDS)).isNotEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role_id=99", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='ADMIN_BOOTSTRAPPED'", Long.class)).isEqualTo(2L);
    }
}
