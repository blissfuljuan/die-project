package com.blissfuljuan.dieapi.mail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development sender: writes the whole message, including any verification link, to the log.
 * Never use in production (links are secrets) — selected only by die.mail.mode=log (see MailConfig).
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.warn("DEV EMAIL (die.mail.mode=log) to={} subject={}\n{}", to, subject, body);
    }
}
