package com.blissfuljuan.dieapi.config;

import com.blissfuljuan.dieapi.config.MailConfig;
import com.blissfuljuan.dieapi.mail.service.LoggingEmailSender;
import com.blissfuljuan.dieapi.mail.service.SmtpEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** die.mail.mode selects the sender; bad or blank values must fail loudly and explain the fix. */
class MailConfigTest {

    private final MailConfig config = new MailConfig();

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(sender);
        return p;
    }

    @Test
    void logModeNeedsNoMailServer() {
        assertThat(config.emailSender("log", "from@x.edu", provider(null))).isInstanceOf(LoggingEmailSender.class);
        assertThat(config.emailSender("  LOG ", "from@x.edu", provider(null))).isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void smtpModeUsesTheConfiguredMailServer() {
        assertThat(config.emailSender("smtp", "from@x.edu", provider(mock(JavaMailSender.class)))).isInstanceOf(SmtpEmailSender.class);
    }

    @Test
    void aBlankModeMeansTheSafeDefaultSmtp_notNoSender() {
        // An empty DIE_MAIL_MODE= line in a .env file used to leave the application without any EmailSender.
        for (String blank : new String[] {"", "   ", null}) {
            assertThat(config.emailSender(blank, "from@x.edu", provider(mock(JavaMailSender.class)))).isInstanceOf(SmtpEmailSender.class);
            assertThatThrownBy(() -> config.emailSender(blank, "from@x.edu", provider(null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.mail.host").hasMessageContaining("DIE_MAIL_MODE=log");
        }
    }

    @Test
    void smtpWithoutAMailServerExplainsWhatToConfigure() {
        assertThatThrownBy(() -> config.emailSender("smtp", "from@x.edu", provider(null)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("needs a mail server");
    }

    @Test
    void unknownModesAreRejectedWithTheirValue() {
        assertThatThrownBy(() -> config.emailSender("smpt", "from@x.edu", provider(null)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("'log' or 'smtp'").hasMessageContaining("'smpt'");
    }
}
