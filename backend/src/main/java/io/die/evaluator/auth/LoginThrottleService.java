package io.die.evaluator.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Failed-attempt throttling per identifier and per client address, backed by login_attempt so it
 * holds across API instances. Identifiers are namespaced ("act:", "pwchange:", "pwreset:" prefixes;
 * login uses the bare email).
 *
 * Password-reset requests are tracked in their own bucket: they must never consume the per-IP login
 * budget, otherwise anonymous reset requests could lock everyone behind one address out of signing in.
 */
@Service
public class LoginThrottleService {

    static final String RESET_PREFIX = "pwreset:";

    private final JdbcClient jdbc;
    private final int maxPerIdentifier;
    private final int maxPerIp;
    private final int windowMinutes;

    public LoginThrottleService(JdbcClient jdbc,
                                @Value("${die.auth.max-failed-per-identifier:5}") int maxPerIdentifier,
                                @Value("${die.auth.max-failed-per-ip:20}") int maxPerIp,
                                @Value("${die.auth.throttle-window-minutes:15}") int windowMinutes) {
        this.jdbc = jdbc;
        this.maxPerIdentifier = maxPerIdentifier;
        this.maxPerIp = maxPerIp;
        this.windowMinutes = windowMinutes;
    }

    /**
     * For login/activation/password-change: throws 429 when the identifier or the address (excluding
     * reset requests) has too many failures. Failures are counted since the identifier's last success.
     */
    public void assertAllowed(String identifier, String clientIp) {
        long byIdentifier = failuresForIdentifier(identifier);
        long byIp = clientIp == null ? 0 : jdbc.sql("""
                SELECT count(*) FROM login_attempt
                WHERE client_ip = :ip AND NOT succeeded
                  AND identifier NOT LIKE 'pwreset:%'
                  AND attempted_at > now() - make_interval(mins => :w)
                """)
            .param("ip", clientIp).param("w", windowMinutes)
            .query(Long.class).single();
        enforce(byIdentifier >= maxPerIdentifier || byIp >= maxPerIp);
    }

    /** For password-reset requests: own per-identifier and per-IP budgets. Same answer for any address. */
    public void assertResetAllowed(String resetKey, String clientIp) {
        long byIdentifier = failuresForIdentifier(resetKey);
        long byIp = clientIp == null ? 0 : jdbc.sql("""
                SELECT count(*) FROM login_attempt
                WHERE client_ip = :ip AND NOT succeeded
                  AND identifier LIKE 'pwreset:%'
                  AND attempted_at > now() - make_interval(mins => :w)
                """)
            .param("ip", clientIp).param("w", windowMinutes)
            .query(Long.class).single();
        enforce(byIdentifier >= maxPerIdentifier || byIp >= maxPerIp);
    }

    public void record(String identifier, String clientIp, boolean succeeded) {
        jdbc.sql("INSERT INTO login_attempt (identifier, client_ip, succeeded) VALUES (:id, :ip, :ok)")
            .param("id", identifier).param("ip", clientIp).param("ok", succeeded)
            .update();
    }

    private long failuresForIdentifier(String identifier) {
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

    private static void enforce(boolean blocked) {
        if (blocked) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later.");
        }
    }
}
