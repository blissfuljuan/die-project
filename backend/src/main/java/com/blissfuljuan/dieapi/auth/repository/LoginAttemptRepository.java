package com.blissfuljuan.dieapi.auth.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence of sign-in/activation/password attempts (login_attempt), shared across API instances. Time is the database clock. */
@Repository
public class LoginAttemptRepository {

    private final JdbcClient jdbc;

    public LoginAttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String identifier, String clientIp, boolean succeeded) {
        jdbc.sql("INSERT INTO login_attempt (identifier, client_ip, succeeded) VALUES (:id, :ip, :ok)")
            .param("id", identifier).param("ip", clientIp).param("ok", succeeded)
            .update();
    }

    /** Failures of one identifier inside the window and after its last success. */
    public long failuresForIdentifier(String identifier, int windowMinutes) {
        return jdbc.sql("""
                SELECT count(*) FROM login_attempt
                WHERE identifier = :id AND NOT succeeded
                  AND attempted_at > now() - make_interval(mins => :w)
                  AND attempted_at > COALESCE(
                        (SELECT max(attempted_at) FROM login_attempt WHERE identifier = :id AND succeeded),
                        '-infinity'::timestamptz)
                """)
            .param("id", identifier).param("w", windowMinutes)
            .query(Long.class).single();
    }

    /** Failures from one address inside the window whose identifier does NOT start with the prefix. */
    public long failuresForIpExcludingPrefix(String clientIp, String prefix, int windowMinutes) {
        return jdbc.sql("""
                SELECT count(*) FROM login_attempt
                WHERE client_ip = :ip AND NOT succeeded
                  AND identifier NOT LIKE :prefix || '%'
                  AND attempted_at > now() - make_interval(mins => :w)
                """)
            .param("ip", clientIp).param("prefix", prefix).param("w", windowMinutes)
            .query(Long.class).single();
    }

    /** Failures from one address inside the window whose identifier starts with the prefix. */
    public long failuresForIpWithPrefix(String clientIp, String prefix, int windowMinutes) {
        return jdbc.sql("""
                SELECT count(*) FROM login_attempt
                WHERE client_ip = :ip AND NOT succeeded
                  AND identifier LIKE :prefix || '%'
                  AND attempted_at > now() - make_interval(mins => :w)
                """)
            .param("ip", clientIp).param("prefix", prefix).param("w", windowMinutes)
            .query(Long.class).single();
    }
}
