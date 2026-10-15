package com.blissfuljuan.dieapi.account.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Issues individual temporary credentials. The plaintext is returned once to the caller and
 * never stored or logged; only the Argon2 hash is persisted.
 *
 * Hashing is deliberately expensive. For a single credential it runs on the calling thread; for bulk
 * issuing (roster commit) the hashes are computed beforehand by {@link CredentialHasher}, outside any
 * database transaction, and only stored here.
 */
@Service
public class TempCredentialService {

    public record Issued(String secret, OffsetDateTime expiresAt) {}

    /** A freshly generated secret and its hash, not yet attached to a user. */
    public record Prepared(String secret, String hash) {}

    // No look-alike characters (0/O, 1/l/I).
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int LENGTH = 14;

    private final SecureRandom random = new SecureRandom();
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final int validDays;

    public TempCredentialService(JdbcClient jdbc, PasswordEncoder encoder,
                                 @Value("${die.auth.temp-credential-days:14}") int validDays) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.validDays = validDays;
    }

    /** Invalidates any live credential for the user, then issues a fresh one. Call inside a transaction. */
    public Issued issue(UUID userId, UUID createdBy) {
        invalidateActive(userId, "RESET");
        String secret = newSecret();
        return store(userId, secret, encoder.encode(secret), createdBy);
    }

    /**
     * Stores credentials that were prepared in advance for brand-new accounts (no live credential to
     * invalidate). Pure database work: cheap enough to run inside the caller's transaction.
     * The result list has the same order as {@code userIds}.
     */
    public List<Issued> storePrepared(List<UUID> userIds, List<Prepared> prepared, UUID createdBy) {
        if (userIds.size() != prepared.size()) {
            throw new IllegalArgumentException("one prepared credential per user is required");
        }
        List<Issued> issued = new ArrayList<>(userIds.size());
        for (int i = 0; i < userIds.size(); i++) {
            issued.add(store(userIds.get(i), prepared.get(i).secret(), prepared.get(i).hash(), createdBy));
        }
        return issued;
    }

    public void invalidateActive(UUID userId, String reason) {
        jdbc.sql("""
                UPDATE temp_credential SET invalidated_at = now(), invalid_reason = :r
                WHERE user_id = :u AND invalidated_at IS NULL
                """)
            .param("u", userId).param("r", reason).update();
    }

    /** A random secret from a CSPRNG (about 80 bits). */
    public String newSecret() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private Issued store(UUID userId, String secret, String hash, UUID createdBy) {
        OffsetDateTime expires = jdbc.sql("""
                INSERT INTO temp_credential (user_id, secret_hash, expires_at, created_by)
                VALUES (:u, :h, now() + make_interval(days => :d), :by)
                RETURNING expires_at
                """)
            .param("u", userId).param("h", hash).param("d", validDays).param("by", createdBy)
            .query((rs, n) -> rs.getObject("expires_at", OffsetDateTime.class)).single();
        return new Issued(secret, expires);
    }
}
