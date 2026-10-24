package com.blissfuljuan.dieapi.mail.service.adapter;

import com.blissfuljuan.dieapi.common.util.PeriodicTask;
import com.blissfuljuan.dieapi.mail.service.MailDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Runs {@link MailDispatcher#dispatchOnce()} on a fixed delay. Off when die.mail.dispatch=inline (tests dispatch right after commit). */
@Component
@ConditionalOnProperty(name = "die.mail.dispatch", havingValue = "background", matchIfMissing = true)
public class MailDispatchRunner extends PeriodicTask {

    public MailDispatchRunner(MailDispatcher dispatcher, @Value("${die.mail.dispatch-interval-seconds:5}") long intervalSeconds) {
        super("mail-dispatcher", intervalSeconds, intervalSeconds, dispatcher::dispatchOnce);
    }
}
