package com.blissfuljuan.dieapi.auth.service;

import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

/**
 * Loads a user by login identifier. Slice 1: verified email only.
 * Student-ID login for PENDING_ACTIVATION accounts is added in slice 3 (activation).
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final JdbcClient jdbc;

    public AppUserDetailsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        String email = identifier.trim().toLowerCase(Locale.ROOT);
        return jdbc.sql("""
                SELECT id, email, first_name, last_name, role, status, password_hash
                FROM app_user
                WHERE lower(email) = :email AND email_verified_at IS NOT NULL AND password_hash IS NOT NULL
                """)
            .param("email", email)
            .query((rs, n) -> (UserDetails) new AppUserDetails(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getString("password_hash")))
            .optional()
            .orElseThrow(() -> new UsernameNotFoundException("unknown"));
    }
}
