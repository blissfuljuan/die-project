package com.blissfuljuan.dieapi.mail.repository;

import com.blissfuljuan.dieapi.mail.model.QueuedMail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Persistence of mail_outbox. Time is the database clock. */
@Repository
public class MailOutboxRepository {

    private final JdbcClient jdbc;

    public MailOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String to, String subject, String body) {
        jdbc.sql("INSERT INTO mail_outbox (to_address, subject, body) VALUES (:to, :subject, :body)")
            .param("to", to).param("subject", subject).param("body", body)
            .update();
    }

    /**
     * Claims up to {@code limit} due messages in one statement: concurrent dispatchers (several API instances) never get the
     * same row, and a claimed row becomes due again after {@code leaseSeconds}, so a dispatcher that dies mid-send loses nothing.
     */
    public List<QueuedMail> claimDue(int limit, int leaseSeconds) {
        return jdbc.sql("""
                UPDATE mail_outbox SET attempts = attempts + 1, next_attempt_at = now() + make_interval(secs => :lease)
                WHERE id IN (SELECT id FROM mail_outbox WHERE next_attempt_at <= now()
                             ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                RETURNING id, to_address, subject, body, attempts
                """)
            .param("lease", leaseSeconds).param("limit", limit)
            .query((rs, n) -> new QueuedMail(rs.getObject("id", UUID.class), rs.getString("to_address"),
                rs.getString("subject"), rs.getString("body"), rs.getInt("attempts")))
            .list();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM mail_outbox WHERE id = :id").param("id", id).update();
    }

    /** Schedules the next attempt after a failed send. The error is a class name only (never a message that might echo content). */
    public void scheduleRetry(UUID id, int delaySeconds, String error) {
        jdbc.sql("UPDATE mail_outbox SET next_attempt_at = now() + make_interval(secs => :d), last_error = :e WHERE id = :id")
            .param("d", delaySeconds).param("e", error).param("id", id).update();
    }
}
