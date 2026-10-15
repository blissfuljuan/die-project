package com.blissfuljuan.dieapi.support;

import com.blissfuljuan.dieapi.mail.service.EmailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Test double that records outgoing mail so tests can read verification links. */
public class CapturingEmailSender implements EmailSender {

    public record Message(String to, String subject, String body) {
        public String token() {
            Matcher m = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(body);
            if (!m.find()) throw new AssertionError("no token in mail body: " + body);
            return m.group(1);
        }
    }

    public final List<Message> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(String to, String subject, String body) {
        sent.add(new Message(to, subject, body));
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        CapturingEmailSender capturingEmailSender() {
            return new CapturingEmailSender();
        }
    }
}
