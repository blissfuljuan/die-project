package com.blissfuljuan.dieapi.auth.model;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Session principal. Serialized into the JDBC session, so keep it small and Serializable.
 *
 * - getUsername() returns the user id (not the email) so sessions can be revoked per user
 *   (spring_session.principal_name) even if the email changes.
 * - The password hash is erased right after authentication (CredentialsContainer), so it is never
 *   persisted in the session store.
 * - isEnabled() is always true on purpose: account status is enforced by a post-authentication check
 *   (see AuthenticationConfig) so that deactivated accounts still pay the password-hashing cost and
 *   are indistinguishable from wrong-password failures by timing. Use isActive() for status.
 */
public class AppUserDetails implements UserDetails, CredentialsContainer, Serializable {

    @Serial
    private static final long serialVersionUID = 2L;

    private final UUID id;
    private final String email;
    private final String firstName;
    private final String lastName;
    private final String role;
    private final String status;
    private String passwordHash;

    public AppUserDetails(UUID id, String email, String firstName, String lastName,
                          String role, String status, String passwordHash) {
        this.id = id;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.role = role;
        this.status = status;
        this.passwordHash = passwordHash;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getRole() { return role; }
    public String getStatus() { return status; }
    public boolean isActive() { return "ACTIVE".equals(status); }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return id.toString(); }
    @Override public boolean isEnabled() { return true; }
    @Override public void eraseCredentials() { this.passwordHash = null; }
}
