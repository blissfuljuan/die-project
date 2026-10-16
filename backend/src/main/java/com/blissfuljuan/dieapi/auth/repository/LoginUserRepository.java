package com.blissfuljuan.dieapi.auth.repository;

import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Reads the account row Spring Security signs in with. */
@Repository
public class LoginUserRepository {

    private final JdbcClient jdbc;

    public LoginUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A user with a verified email and a password, by lower-cased email. */
    public Optional<AppUserDetails> findLoginByEmail(String email) {
        return jdbc.sql("""
                SELECT id, email, first_name, last_name, role, status, password_hash
                FROM app_user
                WHERE lower(email) = :email AND email_verified_at IS NOT NULL AND password_hash IS NOT NULL
                """)
            .param("email", email)
            .query((rs, n) -> new AppUserDetails(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getString("password_hash")))
            .optional();
    }
}
