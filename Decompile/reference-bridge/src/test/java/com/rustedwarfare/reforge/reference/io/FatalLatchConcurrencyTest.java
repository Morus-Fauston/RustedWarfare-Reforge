package com.rustedwarfare.reforge.reference.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Public API checks; the bounded race run does not cover every possible interleaving. */
public final class FatalLatchConcurrencyTest {
    private static final int THREADS = 32;
    private static final int ROUNDS = 100;
    private static final int TIMEOUT_SECONDS = 10;
    private static int checks;

    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static FatalLatch.Failure checkedFailure(FatalLatch latch) {
        try { latch.check(); return null; }
        catch (FatalLatch.Failure failure) { return failure; }
    }

    private static void sequential() {
        FatalLatch latch = new FatalLatch();
        require(!latch.failed(), "new session is healthy");
        require(latch.firstFailure() == null, "new session has no failure");
        latch.check();
        Throwable cause = new IOException("original-cause");
        FatalLatch.Failure first = latch.trip("original-code", "original-message", cause);
        require(latch.failed(), "trip marks failed");
        require(latch.firstFailure() == first, "firstFailure identity");
        require(checkedFailure(latch) == first, "check throws first instance");
        require(latch.trip("later-code", "later-message", new IOException("later")) == first,
                "later trip keeps first instance");
        require(first.getCause() == cause, "cause identity is preserved");
        require("original-code".equals(first.code()), "code is preserved");
        require("original-message".equals(first.getMessage()), "message is preserved");
        FatalLatch other = new FatalLatch();
        require(!other.failed() && other.firstFailure() == null, "sessions are independent");
        other.check();
    }

    private static void handoff() throws Exception {
        final FatalLatch latch = new FatalLatch();
        final Throwable cause = new IOException("handoff-cause");
        final CountDownLatch published = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<FatalLatch.Failure> writer = workers.submit(() -> {
                FatalLatch.Failure first = latch.trip("handoff", "writer", cause);
                published.countDown();
                return first;
            });
            Future<FatalLatch.Failure> reader = workers.submit(() -> {
                if (!published.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    throw new AssertionError("handoff timed out");
                if (!latch.failed()) throw new AssertionError("published failure is visible");
                FatalLatch.Failure first = latch.firstFailure();
                if (checkedFailure(latch) != first || latch.trip("later", "reader", null) != first)
                    throw new AssertionError("handoff identity changed");
                return first;
            });
            FatalLatch.Failure first = writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            require(reader.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) == first, "cross-thread identity");
            require(first.getCause() == cause && "handoff".equals(first.code()), "cross-thread details");
            System.out.println("HANDOFF establishes its own happens-before; not a missing-volatile test");
        } finally {
            workers.shutdownNow();
            if (!workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                throw new AssertionError("handoff workers did not terminate");
        }
    }

    private static final class Observation {
        final int thread;
        final FatalLatch.Failure returned;
        final FatalLatch.Failure visible;
        final FatalLatch.Failure thrown;
        final FatalLatch.Failure later;
        final boolean failed;

        Observation(int thread, FatalLatch latch, String code, Throwable cause) {
            this.thread = thread;
            returned = latch.trip(code, "racing-trip", cause);
            visible = latch.firstFailure();
            thrown = checkedFailure(latch);
            later = latch.trip("must-not-replace", "later", null);
            failed = latch.failed();
        }

        String describe() {
            return "thread=" + thread + " returned=" + identity(returned)
                    + " visible=" + identity(visible) + " thrown=" + identity(thrown)
                    + " later=" + identity(later) + " failed=" + failed;
        }
    }

    private static String identity(FatalLatch.Failure failure) {
        return failure == null ? "null" : Integer.toHexString(System.identityHashCode(failure))
                + ":" + failure.code() + ":cause@"
                + Integer.toHexString(System.identityHashCode(failure.getCause()));
    }

    private static void race() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(THREADS);
        int failedRounds = 0;
        try {
            for (int round = 0; round < ROUNDS; round++) {
                final FatalLatch latch = new FatalLatch();
                final CyclicBarrier ready = new CyclicBarrier(THREADS);
                List<Future<Observation>> results = new ArrayList<Future<Observation>>();
                List<Throwable> causes = new ArrayList<Throwable>();
                final int currentRound = round;
                for (int thread = 0; thread < THREADS; thread++) {
                    final int participant = thread;
                    final Throwable cause = new IOException("round=" + round + " thread=" + thread);
                    causes.add(cause);
                    results.add(workers.submit(() -> {
                        ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        return new Observation(participant, latch,
                                "r" + currentRound + "-t" + participant, cause);
                    }));
                }
                List<Observation> observations = new ArrayList<Observation>();
                for (Future<Observation> result : results)
                    observations.add(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                FatalLatch.Failure first = latch.firstFailure();
                boolean coherent = first != null && latch.failed() && checkedFailure(latch) == first;
                int winner = -1;
                for (int thread = 0; thread < THREADS; thread++)
                    if (first != null && first.getCause() == causes.get(thread)) winner = thread;
                coherent &= winner >= 0 && ("r" + round + "-t" + winner).equals(first.code());
                for (Observation observation : observations) {
                    coherent &= observation.returned == first && observation.visible == first
                            && observation.thrown == first && observation.later == first && observation.failed;
                    checks++;
                }
                System.out.println("RACE round=" + round + " coherent=" + coherent
                        + " final=" + identity(first));
                if (!coherent) {
                    failedRounds++;
                    for (Observation observation : observations)
                        System.out.println("DIVERGENCE round=" + round + " " + observation.describe());
                }
            }
        } finally {
            workers.shutdownNow();
            if (!workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                throw new AssertionError("race workers did not terminate");
        }
        System.out.println("RACE_SUMMARY threads=" + THREADS + " rounds=" + ROUNDS
                + " failedRounds=" + failedRounds + " checks=" + checks);
        require(failedRounds == 0, "first-failure identity diverged in " + failedRounds + " rounds");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("sequential | handoff | race");
        switch (args[0]) {
            case "sequential": sequential(); break;
            case "handoff": handoff(); break;
            case "race": race(); break;
            default: throw new IllegalArgumentException(args[0]);
        }
        System.out.println("PASS FatalLatchConcurrency " + args[0] + " checks=" + checks);
    }
}
