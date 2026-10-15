package io.die.evaluator.account;

import io.die.evaluator.account.AccountRepository.Locked;
import io.die.evaluator.audit.AuditService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Administrative account lifecycle. Every state change is transactional, audited, and revokes the
 * user's sessions where authorities or access change. Admins can never apply these operations to
 * themselves, which also guarantees at least one active admin remains.
 */
@Service
public class AccountService {

    public record CreateUser(String role, String studentNumber, String lastName, String firstName,
                             String middleName, String email) {}

    public record UpdateUser(Long version, String role, String studentNumber, String lastName,
                             String firstName, String middleName, String email) {}

    /** Result of operations that may issue a one-time temporary credential. */
    public record AccountChange(UserView user, String temporaryCredential, OffsetDateTime temporaryCredentialExpiresAt) {}

    private static final Set<String> ROLES = Set.of("STUDENT", "INSTRUCTOR", "ADMIN");

    private final AccountRepository repo;
    private final TempCredentialService credentials;
    private final SessionRevocationService sessions;
    private final AuditService audit;

    public AccountService(AccountRepository repo, TempCredentialService credentials,
                          SessionRevocationService sessions, AuditService audit) {
        this.repo = repo;
        this.credentials = credentials;
        this.sessions = sessions;
        this.audit = audit;
    }

    @Transactional
    public AccountChange create(CreateUser req, UUID actor) {
        String role = validRole(req.role());
        String last = required(req.lastName(), "lastName");
        String first = required(req.firstName(), "firstName");
        String studentNumber = blankToNull(req.studentNumber());
        String email = normalizeEmail(req.email());
        requireIdentity(role, studentNumber, email);

        UUID id = repo.insert(role, studentNumber, last, first, blankToNull(req.middleName()), email, actor);
        var issued = credentials.issue(id, actor);
        audit.record(actor, "USER_CREATED", "user", id.toString(), Map.of("role", role));
        return new AccountChange(repo.find(id).orElseThrow(), issued.secret(), issued.expiresAt());
    }

    @Transactional
    public UserView update(UUID id, UpdateUser req, UUID actor) {
        if (req.version() == null) {
            throw status(HttpStatus.BAD_REQUEST, "version is required");
        }
        Locked cur = lock(id);
        if (cur.version() != req.version()) {
            throw status(HttpStatus.CONFLICT, "The account was modified by someone else; reload and retry");
        }
        String role = req.role() == null ? cur.role() : validRole(req.role());
        boolean roleChanged = !role.equals(cur.role());
        if (roleChanged && id.equals(actor)) {
            throw status(HttpStatus.CONFLICT, "You cannot change your own role");
        }
        String studentNumber = req.studentNumber() == null ? cur.studentNumber() : blankToNull(req.studentNumber());
        String last = req.lastName() == null ? cur.lastName() : required(req.lastName(), "lastName");
        String first = req.firstName() == null ? cur.firstName() : required(req.firstName(), "firstName");
        String middle = req.middleName() == null ? cur.middleName() : blankToNull(req.middleName());

        String email = cur.email();
        if (req.email() != null) {
            String requested = normalizeEmail(req.email());
            if (requested != null && !requested.equals(cur.email())) {
                if (!"PENDING_ACTIVATION".equals(cur.status())) {
                    // Changing a verified login email needs the verification flow (account recovery).
                    throw status(HttpStatus.CONFLICT, "Email can only be changed before activation");
                }
                email = requested;
            }
        }
        requireIdentity(role, studentNumber, email);

        List<String> changed = new ArrayList<>();
        if (roleChanged) changed.add("role");
        if (!java.util.Objects.equals(studentNumber, cur.studentNumber())) changed.add("studentNumber");
        if (!last.equals(cur.lastName()) || !first.equals(cur.firstName())
            || !java.util.Objects.equals(middle, cur.middleName())) changed.add("name");
        if (!java.util.Objects.equals(email, cur.email())) changed.add("email");

        repo.updateProfile(id, role, studentNumber, last, first, middle, email);
        if (roleChanged) {
            audit.record(actor, "ROLE_CHANGED", "user", id.toString(), Map.of("from", cur.role(), "to", role));
            sessions.revokeAllAfterCommit(id); // authorities are cached in the session
        }
        if (!changed.isEmpty()) {
            audit.record(actor, "USER_UPDATED", "user", id.toString(), Map.of("fields", changed));
        }
        return repo.find(id).orElseThrow();
    }

    @Transactional
    public UserView deactivate(UUID id, UUID actor) {
        forbidSelf(id, actor, "deactivate");
        Locked cur = lock(id);
        if (!"DEACTIVATED".equals(cur.status())) {
            repo.setStatus(id, "DEACTIVATED");
            credentials.invalidateActive(id, "LOCKED");
            sessions.revokeAllAfterCommit(id);
            audit.record(actor, "USER_DEACTIVATED", "user", id.toString(), Map.of("previousStatus", cur.status()));
        }
        return repo.find(id).orElseThrow();
    }

    /** Re-enables a deactivated account. Accounts that never completed activation get a fresh temporary credential. */
    @Transactional
    public AccountChange activate(UUID id, UUID actor) {
        Locked cur = lock(id);
        if (!"DEACTIVATED".equals(cur.status())) {
            throw status(HttpStatus.CONFLICT, "Only deactivated accounts can be re-activated");
        }
        boolean canLogIn = cur.hasPassword() && cur.emailVerified();
        repo.setStatus(id, canLogIn ? "ACTIVE" : "PENDING_ACTIVATION");
        TempCredentialService.Issued issued = canLogIn ? null : credentials.issue(id, actor);
        audit.record(actor, "USER_ACTIVATED", "user", id.toString(),
            Map.of("resultingStatus", canLogIn ? "ACTIVE" : "PENDING_ACTIVATION"));
        return new AccountChange(repo.find(id).orElseThrow(),
            issued == null ? null : issued.secret(), issued == null ? null : issued.expiresAt());
    }

    /**
     * Invalidates the user's password and sessions and issues a new temporary credential. The user
     * must re-activate before signing in again.
     */
    @Transactional
    public AccountChange resetCredentials(UUID id, UUID actor) {
        forbidSelf(id, actor, "reset the credentials of");
        Locked cur = lock(id);
        if ("DEACTIVATED".equals(cur.status())) {
            throw status(HttpStatus.CONFLICT, "Re-activate the account before resetting credentials");
        }
        repo.clearPasswordAndSetPending(id);
        var issued = credentials.issue(id, actor);
        sessions.revokeAllAfterCommit(id);
        audit.record(actor, "CREDENTIALS_RESET", "user", id.toString(), Map.of("previousStatus", cur.status()));
        return new AccountChange(repo.find(id).orElseThrow(), issued.secret(), issued.expiresAt());
    }

    @Transactional(readOnly = true)
    public UserView get(UUID id) {
        return repo.find(id).orElseThrow(() -> status(HttpStatus.NOT_FOUND, "User not found"));
    }

    @Transactional(readOnly = true)
    public AccountRepository.PageResult search(String role, String statusFilter, String q, int page, int size) {
        if (role != null) validRole(role);
        if (statusFilter != null && !Set.of("PENDING_ACTIVATION", "ACTIVE", "DEACTIVATED").contains(statusFilter)) {
            throw status(HttpStatus.BAD_REQUEST, "Unknown status");
        }
        return repo.search(role, statusFilter, q, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    // ---- helpers ----

    private Locked lock(UUID id) {
        return repo.lock(id).orElseThrow(() -> status(HttpStatus.NOT_FOUND, "User not found"));
    }

    private static void forbidSelf(UUID id, UUID actor, String verb) {
        if (id.equals(actor)) {
            throw status(HttpStatus.CONFLICT, "You cannot " + verb + " your own account");
        }
    }

    private static void requireIdentity(String role, String studentNumber, String email) {
        if ("STUDENT".equals(role) && studentNumber == null) {
            throw status(HttpStatus.BAD_REQUEST, "studentNumber is required for students");
        }
        if (!"STUDENT".equals(role) && email == null) {
            throw status(HttpStatus.BAD_REQUEST, "email is required for instructors and administrators");
        }
    }

    private static String validRole(String role) {
        if (role == null || !ROLES.contains(role)) {
            throw status(HttpStatus.BAD_REQUEST, "role must be STUDENT, INSTRUCTOR or ADMIN");
        }
        return role;
    }

    private static String required(String value, String field) {
        String v = blankToNull(value);
        if (v == null) throw status(HttpStatus.BAD_REQUEST, field + " is required");
        return v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String normalizeEmail(String v) {
        String s = blankToNull(v);
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException status(HttpStatus s, String reason) {
        return new ResponseStatusException(s, reason);
    }
}
