package io.die.evaluator.roster;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admission control for roster commits, the only request that does bulk password hashing:
 *  - one commit per user at a time (a double click or a script cannot stack up work),
 *  - a global cap on concurrent commits (CPU and memory stay bounded, other users keep working).
 * Refused callers get 429 and can simply retry; nothing has been changed at that point.
 * In-memory: with several API instances each instance enforces its own cap, which is what protects its own pool.
 */
@Component
public class CommitLimiter {

    /** Release with try-with-resources. */
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private final Semaphore global;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public CommitLimiter(@Value("${die.roster.max-concurrent-commits:2}") int maxConcurrent) {
        this.global = new Semaphore(maxConcurrent);
    }

    public Permit acquire(UUID userId) {
        if (!running.add(userId)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "A roster commit of yours is already running; wait for it to finish");
        }
        if (!global.tryAcquire()) {
            running.remove(userId);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "The server is busy committing other rosters; try again in a moment");
        }
        return () -> {
            global.release();
            running.remove(userId);
        };
    }
}
