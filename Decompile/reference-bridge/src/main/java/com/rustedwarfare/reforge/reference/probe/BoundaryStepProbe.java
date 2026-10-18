package com.rustedwarfare.reforge.reference.probe;

import java.util.regex.Pattern;

/** Single-process, single-request observer for the original b(F) call path. */
public final class BoundaryStepProbe {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]+");
    private static Context active;

    private BoundaryStepProbe() {}

    public static synchronized void beginRequest(String requestId, long expectedThreadId) {
        if (active != null) {
            active.failFirst("request-in-flight");
            return;
        }
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            active = Context.failed(requestId, expectedThreadId, "invalid-request-id");
            return;
        }
        if (expectedThreadId <= 0L) {
            active = Context.failed(requestId, expectedThreadId, "invalid-thread-id");
            return;
        }
        active = new Context(requestId, expectedThreadId);
    }

    public static synchronized void onBfEnter(float rawArgument) {
        if (active == null) return;
        long threadId = Thread.currentThread().getId();
        active.enterCount++;
        if (active.enterCount == 1) {
            active.rawArgument = rawArgument;
            active.rawArgumentBits = Float.floatToRawIntBits(rawArgument);
            active.hasArgument = true;
            active.observedThreadId = threadId;
        }
        if (threadId != active.expectedThreadId) active.failFirst("thread-mismatch");
        if (active.inBf) active.failFirst("reentrant-bf");
        else if (active.enterCount > 1) active.failFirst("multiple-bf-enter");
        active.inBf = true;
    }

    public static synchronized void onBfNormalExit() {
        if (active == null) return;
        long threadId = Thread.currentThread().getId();
        active.normalExitCount++;
        if (threadId != active.expectedThreadId) active.failFirst("thread-mismatch");
        if (active.enterCount == 0) active.failFirst("normal-without-enter");
        else if (!active.inBf) active.failFirst("normal-count-mismatch");
        active.inBf = false;
        active.observedThreadId = threadId;
    }

    public static synchronized Snapshot finishRequest() {
        if (active == null) return Snapshot.failed(null, -1L, "no-active-request");
        Context context = active;
        active = null;
        if (context.firstFailure != null) return context.snapshot("FAILED", context.firstFailure);
        if (context.enterCount == 0) return context.snapshot("FAILED", "no-bf-enter");
        if (context.enterCount != 1) return context.snapshot("FAILED", "multiple-bf-enter");
        if (context.normalExitCount == 0) return context.snapshot("FAILED", "unmatched-enter");
        if (context.normalExitCount != 1) return context.snapshot("FAILED", "normal-count-mismatch");
        return context.snapshot("COLLECTED", null);
    }

    public static final class Snapshot {
        private final String requestId;
        private final long expectedThreadId;
        private final long observedThreadId;
        private final int enterCount;
        private final int normalExitCount;
        private final boolean hasArgument;
        private final Float rawArgument;
        private final Integer rawArgumentBits;
        private final String status;
        private final String failureCode;

        private Snapshot(String requestId, long expectedThreadId, long observedThreadId,
                         int enterCount, int normalExitCount, boolean hasArgument,
                         Float rawArgument, Integer rawArgumentBits,
                         String status, String failureCode) {
            this.requestId = requestId;
            this.expectedThreadId = expectedThreadId;
            this.observedThreadId = observedThreadId;
            this.enterCount = enterCount;
            this.normalExitCount = normalExitCount;
            this.hasArgument = hasArgument;
            this.rawArgument = rawArgument;
            this.rawArgumentBits = rawArgumentBits;
            this.status = status;
            this.failureCode = failureCode;
        }

        public String requestId() { return requestId; }
        public long expectedThreadId() { return expectedThreadId; }
        public long observedThreadId() { return observedThreadId; }
        public int enterCount() { return enterCount; }
        public int normalExitCount() { return normalExitCount; }
        public boolean hasArgument() { return hasArgument; }
        public float rawArgument() {
            if (!hasArgument) throw new IllegalStateException("raw argument unavailable");
            return rawArgument.floatValue();
        }
        public int rawArgumentBits() {
            if (!hasArgument) throw new IllegalStateException("raw argument unavailable");
            return rawArgumentBits.intValue();
        }
        public String status() { return status; }
        public String failureCode() { return failureCode; }

        private static Snapshot failed(String requestId, long expectedThreadId, String code) {
            return new Snapshot(requestId, expectedThreadId, -1L, 0, 0, false,
                    null, null, "FAILED", code);
        }
    }

    private static final class Context {
        final String requestId;
        final long expectedThreadId;
        long observedThreadId = -1L;
        int enterCount;
        int normalExitCount;
        boolean inBf;
        boolean hasArgument;
        float rawArgument;
        int rawArgumentBits;
        String firstFailure;

        Context(String requestId, long expectedThreadId) {
            this.requestId = requestId;
            this.expectedThreadId = expectedThreadId;
        }

        static Context failed(String requestId, long expectedThreadId, String code) {
            Context context = new Context(requestId, expectedThreadId);
            context.firstFailure = code;
            return context;
        }

        void failFirst(String code) {
            if (firstFailure == null) firstFailure = code;
        }

        Snapshot snapshot(String status, String failureCode) {
            return new Snapshot(requestId, expectedThreadId, observedThreadId,
                    enterCount, normalExitCount, hasArgument,
                    hasArgument ? Float.valueOf(rawArgument) : null,
                    hasArgument ? Integer.valueOf(rawArgumentBits) : null,
                    status, failureCode);
        }
    }
}
