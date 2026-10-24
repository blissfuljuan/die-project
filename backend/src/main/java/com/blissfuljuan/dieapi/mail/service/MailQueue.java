package com.blissfuljuan.dieapi.mail.service;

import com.blissfuljuan.dieapi.common.util.AfterCommit;
import com.blissfuljuan.dieapi.mail.repository.MailOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write side of the mail outbox: services call {@link #enqueue} inside their own transaction and never talk to SMTP.
 * Delivery is {@link MailDispatcher}'s job. With {@code die.mail.dispatch=inline} (tests) the dispatcher also runs once
 * right after the surrounding transaction commits, so tests can read the captured mail without waiting.
 */
@Service
public class MailQueue {

    private final MailOutboxRepository outbox;
    private final MailDispatcher dispatcher;
    private final boolean inline;

    public MailQueue(MailOutboxRepository outbox, MailDispatcher dispatcher, @Value("${die.mail.dispatch:background}") String dispatch) {
        this.outbox = outbox;
        this.dispatcher = dispatcher;
        this.inline = "inline".equalsIgnoreCase(dispatch);
    }

    @Transactional
    public void enqueue(String to, String subject, String body) {
        outbox.insert(to, subject, body);
        if (inline) {
            AfterCommit.run("inline mail dispatch", dispatcher::dispatchOnce);
        }
    }
}
