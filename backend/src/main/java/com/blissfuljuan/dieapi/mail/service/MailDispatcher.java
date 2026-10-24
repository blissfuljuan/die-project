package com.blissfuljuan.dieapi.mail.service;

import com.blissfuljuan.dieapi.mail.model.QueuedMail;
import com.blissfuljuan.dieapi.mail.repository.MailOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Delivers queued mail through the {@link EmailSender}. Not transactional on purpose: no connection or lock is held while
 * SMTP is slow. Delivery is at-least-once (a crash between sending and deleting resends after the lease). A message that
 * keeps failing is retried with a growing delay and dropped after {@code die.mail.max-attempts}; the log carries ids only.
 */
@Service
public class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);
    private static final int BATCH = 20;

    private final MailOutboxRepository outbox;
    private final EmailSender sender;
    private final int maxAttempts;
    private final int leaseSeconds;

    public MailDispatcher(MailOutboxRepository outbox, EmailSender sender,
                          @Value("${die.mail.max-attempts:6}") int maxAttempts,
                          @Value("${die.mail.lease-seconds:120}") int leaseSeconds) {
        this.outbox = outbox;
        this.sender = sender;
        this.maxAttempts = maxAttempts;
        this.leaseSeconds = leaseSeconds;
    }

    /** One pass over the due messages; returns how many were sent. */
    public int dispatchOnce() {
        List<QueuedMail> due = outbox.claimDue(BATCH, leaseSeconds);
        int sent = 0;
        for (QueuedMail mail : due) {
            try {
                sender.send(mail.to(), mail.subject(), mail.body());
                outbox.delete(mail.id());
                sent++;
            } catch (RuntimeException e) {
                fail(mail, e);
            }
        }
        return sent;
    }

    private void fail(QueuedMail mail, RuntimeException e) {
        if (mail.attempts() >= maxAttempts) {
            outbox.delete(mail.id());
            log.error("mail {} dropped after {} attempts: {}", mail.id(), mail.attempts(), e.getClass().getSimpleName());
            return;
        }
        int delay = Math.min(3600, 30 * mail.attempts() * mail.attempts());
        outbox.scheduleRetry(mail.id(), delay, e.getClass().getSimpleName());
        log.warn("mail {} attempt {} failed ({}); retry in {}s", mail.id(), mail.attempts(), e.getClass().getSimpleName(), delay);
    }
}
