package com.blissfuljuan.dieapi.config;

import com.blissfuljuan.dieapi.mail.service.EmailSender;
import com.blissfuljuan.dieapi.mail.service.LoggingEmailSender;
import com.blissfuljuan.dieapi.mail.service.SmtpEmailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Locale;

/**
 * Chooses the outbound email implementation from {@code die.mail.mode}:
 *  - {@code smtp} (default; also used when the value is blank): real delivery, needs spring.mail.* configured.
 *  - {@code log}: development only, prints messages (including activation/reset links) to the log.
 * Anything else, or smtp without a configured mail server, stops the application at startup with a message
 * that says what to fix (instead of a missing-bean error far away from the cause).
 */
@Configuration
public class MailConfig {

    @Bean
    public EmailSender emailSender(@Value("${die.mail.mode:smtp}") String configuredMode,
                                   @Value("${die.mail.from:no-reply@die.local}") String from,
                                   ObjectProvider<JavaMailSender> mailSender) {
        String mode = configuredMode == null || configuredMode.isBlank() ? "smtp" : configuredMode.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "log" -> new LoggingEmailSender();
            case "smtp" -> {
                JavaMailSender sender = mailSender.getIfAvailable();
                if (sender == null) {
                    throw new IllegalStateException("die.mail.mode=smtp (the default) needs a mail server: set spring.mail.host "
                        + "(and port/credentials) and die.mail.from, or set DIE_MAIL_MODE=log for local development");
                }
                yield new SmtpEmailSender(sender, from);
            }
            default -> throw new IllegalStateException(
                "die.mail.mode must be 'log' or 'smtp' but was '" + configuredMode + "' (check DIE_MAIL_MODE and your .env file)");
        };
    }
}
