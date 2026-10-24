package com.blissfuljuan.dieapi.auth;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.blissfuljuan.dieapi.auth.service.LoginAttemptPurger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Targeted-lockout protection (failures from a stranger's address do not lock the real user out) and attempt purging. */
class AttemptThrottlingIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String EMAIL = "owner@example.edu";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired LoginAttemptPurger purger;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM login_attempt");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("""
            INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at, password_hash)
            VALUES ('COORDINATOR', 'ACTIVE', 'Doe', 'Jane', ?, now(), ?)
            """, EMAIL, encoder.encode(PASSWORD));
    }

    private int login(String password, String fromAddress) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(csrf())
                .with(r -> { r.setRemoteAddr(fromAddress); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}"))
            .andReturn().getResponse().getStatus();
    }

    @Test
    void aStrangersFailuresDoNotLockTheRealUserOut() throws Exception {
        for (int i = 0; i < 6; i++) {
            assertThat(login("guess", "203.0.113.9")).isEqualTo(i < 5 ? 401 : 429);
        }
        // The attacker is throttled, but the owner signing in from another address still gets through.
        assertThat(login(PASSWORD, "198.51.100.7")).isEqualTo(200);
    }

    @Test
    void distributedGuessingStillHitsTheGlobalCap() throws Exception {
        // 30 failures from 30 different addresses (3 each stays under every per-address limit).
        for (int i = 0; i < 30; i++) {
            login("guess", "192.0.2." + (i + 1));
        }
        assertThat(login(PASSWORD, "198.51.100.7")).isEqualTo(429);
    }

    @Test
    void purgeRemovesOnlyOldAttempts() {
        jdbc.update("INSERT INTO login_attempt (identifier, client_ip, succeeded, attempted_at) VALUES ('a', '1.1.1.1', false, now() - interval '8 days')");
        jdbc.update("INSERT INTO login_attempt (identifier, client_ip, succeeded, attempted_at) VALUES ('b', '1.1.1.1', false, now() - interval '1 hour')");

        assertThat(purger.purgeOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_attempt WHERE identifier = 'b'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_attempt WHERE identifier = 'a'", Integer.class)).isZero();
    }
}
