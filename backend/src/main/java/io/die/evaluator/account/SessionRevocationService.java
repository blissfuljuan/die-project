package io.die.evaluator.account;

import java.util.UUID;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes server-side sessions of a user (sessions are indexed by principal name = user id).
 * Revocation is deferred to after commit so a concurrent login cannot re-create a session from
 * the pre-change state. It runs in its own transaction because writes made inside afterCommit
 * would otherwise join the already-completed outer transaction and never be committed.
 */
@Service
public class SessionRevocationService {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final TransactionTemplate requiresNew;

    public SessionRevocationService(FindByIndexNameSessionRepository<? extends Session> sessions,
                                    PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Revoke every session of the user once the surrounding transaction commits. */
    public void revokeAllAfterCommit(UUID userId) {
        revokeAllExceptAfterCommit(userId, null);
    }

    /** Revoke all sessions except {@code keepSessionId} (e.g. the one that just changed the password). */
    public void revokeAllExceptAfterCommit(UUID userId, String keepSessionId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    revokeAllExcept(userId, keepSessionId);
                }
            });
        } else {
            revokeAllExcept(userId, keepSessionId);
        }
    }

    public int revokeAll(UUID userId) {
        return revokeAllExcept(userId, null);
    }

    public int revokeAllExcept(UUID userId, String keepSessionId) {
        Integer count = requiresNew.execute(status -> {
            var found = sessions.findByPrincipalName(userId.toString());
            int deleted = 0;
            for (String id : found.keySet()) {
                if (!id.equals(keepSessionId)) {
                    sessions.deleteById(id);
                    deleted++;
                }
            }
            return deleted;
        });
        return count == null ? 0 : count;
    }
}
