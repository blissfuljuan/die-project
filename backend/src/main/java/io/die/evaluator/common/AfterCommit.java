package io.die.evaluator.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs side effects (email, messaging) only once the surrounding transaction has committed. */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {}

    /** Failures are logged, never propagated: the committed state change must not be reported as failed. */
    public static void run(String description, Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.error("After-commit action failed: {}", description, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }
}
