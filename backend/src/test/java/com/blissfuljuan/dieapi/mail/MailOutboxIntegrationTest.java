package com.blissfuljuan.dieapi.mail;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.blissfuljuan.dieapi.mail.service.MailDispatcher;
import com.blissfuljuan.dieapi.mail.service.MailQueue;
import com.blissfuljuan.dieapi.support.CapturingEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The mail outbox: queued with the caller's transaction, delivered after commit, retried, dropped after the last attempt. */
@Import(CapturingEmailSender.Config.class)
class MailOutboxIntegrationTest extends PostgresIntegrationTest {

    @Autowired MailQueue queue;
    @Autowired MailDispatcher dispatcher;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean CapturingEmailSender mail;

    @BeforeEach
    void clear() {
        mail.sent.clear();
        Mockito.reset(mail);
    }

    private int queued() {
        return jdbc.queryForObject("SELECT count(*) FROM mail_outbox", Integer.class);
    }

    @Test
    void committedMailIsSentAndTheRowDeleted() {
        tx.executeWithoutResult(s -> queue.enqueue("a@example.edu", "Hello", "body"));

        assertThat(mail.sent).extracting(CapturingEmailSender.Message::to).containsExactly("a@example.edu");
        assertThat(queued()).isZero();
    }

    @Test
    void rolledBackTransactionSendsNothingAndLeavesNoRow() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            queue.enqueue("a@example.edu", "Hello", "body");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(mail.sent).isEmpty();
        assertThat(queued()).isZero();
    }

    @Test
    void failedSendKeepsTheRowForARetryThatThenSucceeds() {
        Mockito.doThrow(new IllegalStateException("smtp down")).when(mail).send(Mockito.any(), Mockito.any(), Mockito.any());
        tx.executeWithoutResult(s -> queue.enqueue("a@example.edu", "Hello", "body"));

        assertThat(queued()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM mail_outbox", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM mail_outbox", String.class)).isEqualTo("IllegalStateException");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() FROM mail_outbox", Boolean.class)).isTrue();
        // Not due yet: another pass does nothing.
        assertThat(dispatcher.dispatchOnce()).isZero();

        Mockito.doCallRealMethod().when(mail).send(Mockito.any(), Mockito.any(), Mockito.any());
        jdbc.update("UPDATE mail_outbox SET next_attempt_at = now() - interval '1 second'");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(mail.sent).hasSize(1);
        assertThat(queued()).isZero();
    }

    @Test
    void aMessageThatKeepsFailingIsDroppedAfterTheLastAttempt() {
        Mockito.doThrow(new IllegalStateException("smtp down")).when(mail).send(Mockito.any(), Mockito.any(), Mockito.any());
        tx.executeWithoutResult(s -> queue.enqueue("a@example.edu", "Hello", "body"));
        // Attempts so far: 1. Jump to the last allowed attempt (die.mail.max-attempts defaults to 6).
        jdbc.update("UPDATE mail_outbox SET attempts = 5, next_attempt_at = now() - interval '1 second'");

        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(queued()).isZero();
    }

    @Test
    void aClaimedMessageIsNotHandedOutTwice() {
        Mockito.doThrow(new IllegalStateException("smtp down")).when(mail).send(Mockito.any(), Mockito.any(), Mockito.any());
        tx.executeWithoutResult(s -> queue.enqueue("a@example.edu", "Hello", "body"));
        jdbc.update("UPDATE mail_outbox SET next_attempt_at = now() - interval '1 second'");
        // Simulate a dispatcher that claimed the row (lease in the future) and has not finished.
        jdbc.update("UPDATE mail_outbox SET next_attempt_at = now() + interval '2 minutes'");

        Mockito.doCallRealMethod().when(mail).send(Mockito.any(), Mockito.any(), Mockito.any());
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(mail.sent).isEmpty();
    }
}
