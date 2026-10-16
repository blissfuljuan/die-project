package com.blissfuljuan.dieapi.account.service;

import com.blissfuljuan.dieapi.account.model.AccountStatus;
import com.blissfuljuan.dieapi.account.model.CreateUser;
import com.blissfuljuan.dieapi.account.model.LockedAccount;
import com.blissfuljuan.dieapi.account.model.UpdateUser;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.account.repository.AccountRepository;
import com.blissfuljuan.dieapi.account.view.AccountChange;
import com.blissfuljuan.dieapi.account.view.UserPage;
import com.blissfuljuan.dieapi.account.view.UserView;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Administrative account lifecycle. Every state change is transactional, audited, and revokes the
 * user's sessions where authorities or access change. Admins can never apply these operations to
 * themselves, which also guarantees at least one active admin remains.
 */
@Service
public class AccountService {

    private final AccountRepository repo;
    private final TempCredentialService credentials;
    private final SessionRevocationService sessions;
    private final ApplicationEventPublisher events;

    public AccountService(AccountRepository repo, TempCredentialService credentials,
                          SessionRevocationService sessions, ApplicationEventPublisher events) {
        this.repo = repo;
        this.credentials = credentials;
        this.sessions = sessions;
        this.events = events;
    }

    @Transactional
    public AccountChange create(CreateUser req, UUID actor) {
        UserRole role = validRole(req.role());
        String last = required(req.lastName(), "lastName");
        String first = required(req.firstName(), "firstName");
        String studentNumber = blankToNull(req.studentNumber());
        String email = normalizeEmail(req.email());
        requireIdentity(role, studentNumber, email);

        UUID id = repo.insert(role, studentNumber, last, first, blankToNull(req.middleName()), email, actor);
        var issued = credentials.issue(id, actor);
        events.publishEvent(AuditEvent.of(actor, AuditAction.USER_CREATED, AuditResource.USER, id.toString(), Map.of("role", role.name())));
        return new AccountChange(repo.find(id).orElseThrow(), issued.secret(), issued.expiresAt());
    }

    @Transactional
    public UserView update(UUID id, UpdateUser req, UUID actor) {
        if (req.version() == null) {
            throw ApiException.badRequest("VERSION_REQUIRED", "version is required");
        }
        LockedAccount cur = lock(id);
        if (cur.version() != req.version()) {
            throw ApiException.conflict("ACCOUNT_VERSION_CONFLICT", "The account was modified by someone else; reload and retry");
        }
        UserRole role = req.role() == null ? cur.role() : validRole(req.role());
        boolean roleChanged = role != cur.role();
        if (roleChanged && id.equals(actor)) {
            throw ApiException.conflict("CANNOT_CHANGE_OWN_ROLE", "You cannot change your own role");
        }
        String studentNumber = req.studentNumber() == null ? cur.studentNumber() : blankToNull(req.studentNumber());
        String last = req.lastName() == null ? cur.lastName() : required(req.lastName(), "lastName");
        String first = req.firstName() == null ? cur.firstName() : required(req.firstName(), "firstName");
        String middle = req.middleName() == null ? cur.middleName() : blankToNull(req.middleName());

        String email = cur.email();
        if (req.email() != null) {
            String requested = normalizeEmail(req.email());
            if (requested != null && !requested.equals(cur.email())) {
                if (cur.status() != AccountStatus.PENDING_ACTIVATION) {
                    // Changing a verified login email needs the verification flow (account recovery).
                    throw ApiException.conflict("EMAIL_CHANGE_NOT_ALLOWED", "Email can only be changed before activation");
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
            events.publishEvent(AuditEvent.of(actor, AuditAction.ROLE_CHANGED, AuditResource.USER, id.toString(), Map.of("from", cur.role().name(), "to", role.name())));
            sessions.revokeAllAfterCommit(id); // authorities are cached in the session
        }
        if (!changed.isEmpty()) {
            events.publishEvent(AuditEvent.of(actor, AuditAction.USER_UPDATED, AuditResource.USER, id.toString(), Map.of("fields", changed)));
        }
        return repo.find(id).orElseThrow();
    }

    @Transactional
    public UserView deactivate(UUID id, UUID actor) {
        forbidSelf(id, actor, "deactivate");
        LockedAccount cur = lock(id);
        if (cur.status() != AccountStatus.DEACTIVATED) {
            repo.setStatus(id, AccountStatus.DEACTIVATED);
            credentials.invalidateActive(id, "LOCKED");
            sessions.revokeAllAfterCommit(id);
            events.publishEvent(AuditEvent.of(actor, AuditAction.USER_DEACTIVATED, AuditResource.USER, id.toString(), Map.of("previousStatus", cur.status().name())));
        }
        return repo.find(id).orElseThrow();
    }

    /** Re-enables a deactivated account. Accounts that never completed activation get a fresh temporary credential. */
    @Transactional
    public AccountChange activate(UUID id, UUID actor) {
        LockedAccount cur = lock(id);
        if (cur.status() != AccountStatus.DEACTIVATED) {
            throw ApiException.conflict("ACCOUNT_NOT_DEACTIVATED", "Only deactivated accounts can be re-activated");
        }
        boolean canLogIn = cur.hasPassword() && cur.emailVerified();
        AccountStatus resulting = canLogIn ? AccountStatus.ACTIVE : AccountStatus.PENDING_ACTIVATION;
        repo.setStatus(id, resulting);
        TempCredentialService.Issued issued = canLogIn ? null : credentials.issue(id, actor);
        events.publishEvent(AuditEvent.of(actor, AuditAction.USER_ACTIVATED, AuditResource.USER, id.toString(),
            Map.of("resultingStatus", resulting.name())));
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
        LockedAccount cur = lock(id);
        if (cur.status() == AccountStatus.DEACTIVATED) {
            throw ApiException.conflict("ACCOUNT_DEACTIVATED", "Re-activate the account before resetting credentials");
        }
        repo.clearPasswordAndSetPending(id);
        var issued = credentials.issue(id, actor);
        sessions.revokeAllAfterCommit(id);
        events.publishEvent(AuditEvent.of(actor, AuditAction.CREDENTIALS_RESET, AuditResource.USER, id.toString(), Map.of("previousStatus", cur.status().name())));
        return new AccountChange(repo.find(id).orElseThrow(), issued.secret(), issued.expiresAt());
    }

    @Transactional(readOnly = true)
    public UserView get(UUID id) {
        return repo.find(id).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found"));
    }

    @Transactional(readOnly = true)
    public UserPage search(String role, String statusFilter, String q, int page, int size) {
        UserRole roleFilter = role == null ? null : validRole(role);
        AccountStatus parsedStatus = null;
        if (statusFilter != null) {
            parsedStatus = AccountStatus.parse(statusFilter);
            if (parsedStatus == null) throw ApiException.badRequest("INVALID_STATUS_FILTER", "Unknown status");
        }
        return repo.search(roleFilter, parsedStatus, q, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    // ---- helpers ----

    private LockedAccount lock(UUID id) {
        return repo.lock(id).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found"));
    }

    private static void forbidSelf(UUID id, UUID actor, String verb) {
        if (id.equals(actor)) {
            throw ApiException.conflict("SELF_ACTION_FORBIDDEN", "You cannot " + verb + " your own account");
        }
    }

    private static void requireIdentity(UserRole role, String studentNumber, String email) {
        if (role == UserRole.STUDENT && studentNumber == null) {
            throw ApiException.badRequest("STUDENT_NUMBER_REQUIRED", "studentNumber is required for students");
        }
        if (role != UserRole.STUDENT && email == null) {
            throw ApiException.badRequest("EMAIL_REQUIRED", "email is required for instructors and administrators");
        }
    }

    private static UserRole validRole(String role) {
        UserRole parsed = UserRole.parse(role);
        if (parsed == null) {
            throw ApiException.badRequest("INVALID_ROLE", "role must be STUDENT, INSTRUCTOR or ADMIN");
        }
        return parsed;
    }

    private static String required(String value, String field) {
        String v = blankToNull(value);
        if (v == null) throw ApiException.badRequest("FIELD_REQUIRED", field + " is required");
        return v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String normalizeEmail(String v) {
        String s = blankToNull(v);
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
