package com.blissfuljuan.dieapi.audit;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Audit events keep the guarantees of the old inline call: same transaction, still written without one. */
class AuditEventIntegrationTest extends PostgresIntegrationTest {

    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private int rows(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Integer.class, action);
    }

    @Test
    void auditRowCommitsWithTheSurroundingTransaction() {
        tx.executeWithoutResult(s -> events.publishEvent(
            AuditEvent.of(null, AuditAction.CLASS_UPDATED, "class", "c-1", Map.of("k", "v"))));

        List<Map<String, Object>> found = jdbc.queryForList(
            "SELECT action, resource_type, resource_id, details::text AS d FROM audit_log");
        assertThat(found).hasSize(1);
        assertThat(found.get(0)).containsEntry("action", "CLASS_UPDATED").containsEntry("resource_type", "class")
            .containsEntry("resource_id", "c-1");
        assertThat((String) found.get(0).get("d")).contains("\"k\"").contains("\"v\"");
    }

    @Test
    void auditRowRollsBackWithTheSurroundingTransaction() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            events.publishEvent(AuditEvent.of(null, AuditAction.CLASS_ARCHIVED, "class", "c-2", Map.of()));
            assertThat(rows("CLASS_ARCHIVED")).isEqualTo(1); // written inside the open transaction
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rows("CLASS_ARCHIVED")).isZero();
    }

    @Test
    void auditRowIsWrittenWhenNoTransactionIsActive() {
        events.publishEvent(AuditEvent.of(null, AuditAction.LOGIN_FAILED, "auth", null, Map.of("ip", "1.2.3.4")));

        assertThat(rows("LOGIN_FAILED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resource_id FROM audit_log", String.class)).isNull();
    }

    @Test
    void nullDetailsAreStoredAsEmptyObject() {
        events.publishEvent(AuditEvent.of(null, AuditAction.LOGOUT, "user", "u", null));
        assertThat(jdbc.queryForObject("SELECT details::text FROM audit_log", String.class)).isEqualTo("{}");
    }
}
