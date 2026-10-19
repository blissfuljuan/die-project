package com.blissfuljuan.dieapi.messaging.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Consumer dedupe (table inbox_message). */
@Repository
public class InboxRepository {

    private final JdbcClient jdbc;

    public InboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True if this (consumer, message) was recorded now; false if it had been handled before. */
    public boolean recordIfNew(String consumer, UUID messageId) {
        return jdbc.sql("""
                INSERT INTO inbox_message (consumer, message_id) VALUES (:c, :m) ON CONFLICT DO NOTHING
                """).param("c", consumer).param("m", messageId).update() == 1;
    }
}
