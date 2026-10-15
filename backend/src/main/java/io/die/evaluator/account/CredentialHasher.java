package io.die.evaluator.account;

import io.die.evaluator.account.TempCredentialService.Prepared;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bulk password hashing on a small, dedicated, bounded thread pool.
 *
 * Why not the JVM common pool inside the commit transaction (the previous design): Argon2 is deliberately
 * slow (tens of ms and 16 MB each), so 500 hashes held a database connection, the import row lock and the
 * class lock the whole time and competed with the rest of the JVM for CPU. Now:
 *  - the caller hashes BEFORE opening its transaction (no connection, no locks held),
 *  - at most {@code hash-threads} hashes run at once (bounded CPU and memory),
 *  - the queue is bounded and a deadline applies; overload is a clean 503, never a hang.
 */
@Component
public class CredentialHasher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CredentialHasher.class);

    private final Supplier<String> secrets;
    private final PasswordEncoder encoder;
    private final long timeoutSeconds;
    private final ThreadPoolExecutor executor;

    @Autowired
    public CredentialHasher(TempCredentialService credentials, PasswordEncoder encoder,
                            @Value("${die.credentials.hash-threads:4}") int threads,
                            @Value("${die.credentials.hash-queue:1000}") int queueCapacity,
                            @Value("${die.credentials.hash-timeout-seconds:120}") long timeoutSeconds) {
        this(credentials::newSecret, encoder, threads, queueCapacity, timeoutSeconds);
    }

    CredentialHasher(Supplier<String> secrets, PasswordEncoder encoder, int threads, int queueCapacity, long timeoutSeconds) {
        this.secrets = secrets;
        this.encoder = encoder;
        this.timeoutSeconds = timeoutSeconds;
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(queueCapacity), runnable -> {
                Thread t = new Thread(runnable, "credential-hash-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Generates {@code count} secrets and their hashes, in order. Call it OUTSIDE any database transaction.
     * @throws ResponseStatusException 503 when the pool is saturated or the deadline passes
     */
    public List<Prepared> prepare(int count) {
        List<Future<Prepared>> futures = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    String secret = secrets.get();
                    return new Prepared(secret, encoder.encode(secret));
                }));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            List<Prepared> out = new ArrayList<>(count);
            for (Future<Prepared> f : futures) {
                out.add(f.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return out;
        } catch (RejectedExecutionException e) {
            cancel(futures);
            throw busy("Credential hashing is saturated; try again shortly");
        } catch (TimeoutException e) {
            cancel(futures);
            log.warn("Credential hashing exceeded {}s for {} credentials", timeoutSeconds, count);
            throw busy("Credential hashing took too long; try again shortly");
        } catch (InterruptedException e) {
            cancel(futures);
            Thread.currentThread().interrupt();
            throw busy("Interrupted while hashing credentials");
        } catch (ExecutionException e) {
            cancel(futures);
            throw new IllegalStateException("Credential hashing failed", e.getCause());
        }
    }

    private static void cancel(List<Future<Prepared>> futures) {
        futures.forEach(f -> f.cancel(true));
    }

    private static ResponseStatusException busy(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
