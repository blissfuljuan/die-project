package com.blissfuljuan.dieapi.mail.model;

import java.util.UUID;

/** An email waiting in the mail outbox. {@code attempts} counts this claim. The body may hold a single-use link: never log it. */
public record QueuedMail(UUID id, String to, String subject, String body, int attempts) {

    @Override
    public String toString() {
        return "QueuedMail[id=" + id + ", attempts=" + attempts + "]";
    }
}
