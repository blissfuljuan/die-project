package com.blissfuljuan.die.mail.service;

/** Outbound email port. Implementations: log (dev), SMTP (production). */
public interface EmailSender {

    void send(String to, String subject, String body);
}
