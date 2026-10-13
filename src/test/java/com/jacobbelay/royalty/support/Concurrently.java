package com.jacobbelay.royalty.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs the same action on N threads that all start at the same instant, to provoke race conditions on purpose.
 *
 * <p>The trick is the "starting gun": every thread first waits on one {@link CountDownLatch}; only when all
 * are parked there do we count it down, releasing them together. Without it, thread 1 often finishes before
 * thread 8 even starts and the race never happens, so the test passes for the wrong reason.
 *
 * <p>Each thread calls a Spring service, so each gets its own transaction and its own database connection
 * (HikariCP's default pool is 10, so keep N at or below ~8). Used by StatementImportIT and PayoutIT.
 */
public final class Concurrently {

    private Concurrently() {
    }

    @FunctionalInterface
    public interface Action<T> {
        T run(int threadIndex) throws Exception;
    }

    /** Either a value or the exception a thread ended with, so tests can count successes and failures. */
    public record Outcome<T>(T value, Throwable error) {
        public boolean succeeded() {
            return error == null;
        }
    }

    public static <T> List<Outcome<T>> run(int threads, Action<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();                       // everyone parks here...
                    try {
                        return new Outcome<>(action.run(index), null);
                    } catch (Throwable t) {
                        return new Outcome<T>(null, t);
                    }
                }));
            }
            ready.await();
            go.countDown();                           // ...and is released together
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
