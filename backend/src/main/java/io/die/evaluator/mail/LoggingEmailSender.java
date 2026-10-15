package io.die.evaluator.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development sender: writes the whole message, including any verification link, to the log.
 * Never use in production (links are secrets) — set die.mail.mode=smtp there.
 */
@Component
@ConditionalOnProperty(name = "die.mail.mode", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.warn("DEV EMAIL (die.mail.mode=log) to={} subject={}\n{}", to, subject, body);
    }
}
