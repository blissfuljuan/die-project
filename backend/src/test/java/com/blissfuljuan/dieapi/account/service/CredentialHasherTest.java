package com.blissfuljuan.dieapi.account.service;

import com.blissfuljuan.dieapi.account.service.CredentialHasher;
import com.blissfuljuan.dieapi.account.service.TempCredentialService.Prepared;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bulk hashing pool is bounded: limited parallelism, bounded queue, bounded wait. No Spring, no database. */
class CredentialHasherTest {

    /** Hash = "H(" + secret + ")"; tracks how many encodes run at the same time. */
    static class TrackingEncoder implements PasswordEncoder {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final long sleepMillis;
        final CountDownLatch gate;

        TrackingEncoder(long sleepMillis, CountDownLatch gate) {
            this.sleepMillis = sleepMillis;
            this.gate = gate;
        }

        @Override
        public String encode(CharSequence raw) {
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                if (gate != null) gate.await(30, TimeUnit.SECONDS);
                if (sleepMillis > 0) Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            return "H(" + raw + ")";
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            return encoded.equals("H(" + raw + ")");
        }
    }

    private static CredentialHasher hasher(TrackingEncoder encoder, int threads, int queue, long timeoutSeconds) {
        AtomicInteger counter = new AtomicInteger();
        return new CredentialHasher(() -> "secret-" + counter.incrementAndGet(), encoder, threads, queue, timeoutSeconds);
    }

    @Test
    void neverRunsMoreHashesAtOnceThanTheConfiguredThreadsAndKeepsEverySecretWithItsOwnHash() {
        TrackingEncoder encoder = new TrackingEncoder(30, null);
        CredentialHasher hasher = hasher(encoder, 2, 100, 30);

        List<Prepared> prepared = hasher.prepare(24);

        assertThat(prepared).hasSize(24);
        assertThat(encoder.peak.get()).as("peak parallel hashes").isLessThanOrEqualTo(2).isGreaterThanOrEqualTo(1);
        assertThat(new HashSet<>(prepared.stream().map(Prepared::secret).toList())).hasSize(24);
        for (Prepared p : prepared) {
            assertThat(encoder.matches(p.secret(), p.hash())).as("hash belongs to its secret").isTrue();
        }
        hasher.destroy();
    }

    @Test
    void zeroCredentialsIsANoop() {
        CredentialHasher hasher = hasher(new TrackingEncoder(0, null), 2, 10, 5);
        assertThat(hasher.prepare(0)).isEmpty();
        hasher.destroy();
    }

    @Test
    void aSaturatedQueueIsRefusedCleanlyInsteadOfBlocking() {
        CountDownLatch gate = new CountDownLatch(1);                // hashes block until released
        TrackingEncoder encoder = new TrackingEncoder(0, gate);
        CredentialHasher hasher = hasher(encoder, 1, 2, 30);          // 1 running + 2 queued = room for 3

        long started = System.nanoTime();
        assertThatThrownBy(() -> hasher.prepare(10))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat((System.nanoTime() - started) / 1_000_000).as("refused immediately").isLessThan(2_000);

        gate.countDown();
        hasher.destroy();
    }

    @Test
    void aStuckPoolEndsInATimeoutNotAHang() {
        CountDownLatch gate = new CountDownLatch(1);
        CredentialHasher hasher = hasher(new TrackingEncoder(0, gate), 1, 10, 1);

        long started = System.nanoTime();
        assertThatThrownBy(() -> hasher.prepare(2))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat((System.nanoTime() - started) / 1_000_000).isBetween(900L, 6_000L);

        gate.countDown();
        hasher.destroy();
    }
}
