package com.rustedwarfare.reforge.reference.probe;

/** BO-v0.2 public-seam checks; fixture values are literal contract oracles. */
public final class BoundaryStepProbeTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        j01CollectedSnapshot();
        j02RawFloatBits();
        j03NoEnter();
        j04UnmatchedEnter();
        j05NormalWithoutEnter();
        j06ReentrantAndMultiple();
        j07ThreadMismatch();
        j08NestedBeginAndImmutability();
        j09InvalidInputsAndUnscopedCallbacks();
        System.out.println("BoundaryStepProbeTest PASS J01-J09 assertions=" + assertions);
    }

    private static long threadId() { return Thread.currentThread().getId(); }

    private static void j01CollectedSnapshot() {
        long id = threadId();
        BoundaryStepProbe.beginRequest("r1", id);
        BoundaryStepProbe.onBfEnter(1.25f);
        BoundaryStepProbe.onBfNormalExit();
        BoundaryStepProbe.Snapshot s = BoundaryStepProbe.finishRequest();
        eq("r1", s.requestId());
        eq(id, s.expectedThreadId());
        eq(id, s.observedThreadId());
        eq(1, s.enterCount());
        eq(1, s.normalExitCount());
        eq(true, s.hasArgument());
        eq(1.25f, s.rawArgument());
        eq(0x3fa00000, s.rawArgumentBits());
        collected(s);
        System.out.println("J01 positive1.25/counts/threadfields PASS");
    }

    private static void j02RawFloatBits() {
        int[] bits = {0x80000000, 0x7fc00001, 0x7f800000, 0xff800000};
        float[] values = {-0.0f, Float.intBitsToFloat(0x7fc00001),
                          Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int i = 0; i < bits.length; i++) {
            BoundaryStepProbe.beginRequest("raw-" + i, threadId());
            BoundaryStepProbe.onBfEnter(values[i]);
            BoundaryStepProbe.onBfNormalExit();
            BoundaryStepProbe.Snapshot s = BoundaryStepProbe.finishRequest();
            collected(s);
            eq(bits[i], s.rawArgumentBits());
            eq(bits[i], Float.floatToRawIntBits(s.rawArgument()));
        }
        System.out.println("J02 raw -0/NaN payload/infinity PASS");
    }

    private static void j03NoEnter() {
        BoundaryStepProbe.beginRequest("empty", threadId());
        BoundaryStepProbe.Snapshot s = BoundaryStepProbe.finishRequest();
        failed(s, "no-bf-enter");
        eq(0, s.enterCount());
        eq(0, s.normalExitCount());
        eq(-1L, s.observedThreadId());
        noArgument(s);
        System.out.println("J03 no-enter/raw getters unavailable PASS");
    }

    private static void j04UnmatchedEnter() {
        BoundaryStepProbe.beginRequest("unmatched", threadId());
        BoundaryStepProbe.onBfEnter(1.25f);
        BoundaryStepProbe.Snapshot s = BoundaryStepProbe.finishRequest();
        failed(s, "unmatched-enter");
        eq(1, s.enterCount());
        eq(0, s.normalExitCount());
        eq(0x3fa00000, s.rawArgumentBits());
        System.out.println("J04 unmatched without invented exception exit PASS");
    }

    private static void j05NormalWithoutEnter() {
        BoundaryStepProbe.beginRequest("normal-only", threadId());
        BoundaryStepProbe.onBfNormalExit();
        BoundaryStepProbe.Snapshot s = BoundaryStepProbe.finishRequest();
        failed(s, "normal-without-enter");
        eq(0, s.enterCount());
        eq(1, s.normalExitCount());
        noArgument(s);
        System.out.println("J05 normal-without-enter before structural no-enter PASS");
    }

    private static void j06ReentrantAndMultiple() {
        BoundaryStepProbe.beginRequest("reentrant", threadId());
        BoundaryStepProbe.onBfEnter(1.25f);
        BoundaryStepProbe.onBfEnter(2.5f);
        BoundaryStepProbe.onBfNormalExit();
        BoundaryStepProbe.onBfNormalExit();
        BoundaryStepProbe.Snapshot nested = BoundaryStepProbe.finishRequest();
        failed(nested, "reentrant-bf");
        eq(2, nested.enterCount());
        eq(2, nested.normalExitCount());
        eq(0x3fa00000, nested.rawArgumentBits());
        BoundaryStepProbe.beginRequest("serial", threadId());
        BoundaryStepProbe.onBfEnter(1.25f);
        BoundaryStepProbe.onBfNormalExit();
        BoundaryStepProbe.onBfEnter(2.5f);
        BoundaryStepProbe.onBfNormalExit();
        BoundaryStepProbe.Snapshot serial = BoundaryStepProbe.finishRequest();
        failed(serial, "multiple-bf-enter");
        eq(2, serial.enterCount());
        eq(2, serial.normalExitCount());
        eq(0x3fa00000, serial.rawArgumentBits());
        System.out.println("J06 reentrant/serial-multiple/first raw PASS");
    }

    private static void j07ThreadMismatch() throws InterruptedException {
        long main = threadId();
        BoundaryStepProbe.beginRequest("worker-enter", main);
        Thread t = new Thread(new Runnable() {
            @Override public void run() { BoundaryStepProbe.onBfEnter(1.25f); }
        });
        t.start();
        t.join();
        BoundaryStepProbe.Snapshot s = BoundaryStepProbe.finishRequest();
        failed(s, "thread-mismatch");
        eq(1, s.enterCount());
        eq(0, s.normalExitCount());
        eq(t.getId(), s.observedThreadId());
        eq(0x3fa00000, s.rawArgumentBits());
        BoundaryStepProbe.beginRequest("worker-normal", main);
        BoundaryStepProbe.onBfEnter(1.25f);
        Thread normal = new Thread(new Runnable() {
            @Override public void run() { BoundaryStepProbe.onBfNormalExit(); }
        });
        normal.start();
        normal.join();
        s = BoundaryStepProbe.finishRequest();
        failed(s, "thread-mismatch");
        eq(1, s.enterCount());
        eq(1, s.normalExitCount());
        System.out.println("J07 real other thread/first failure priority PASS");
    }

    private static void j08NestedBeginAndImmutability() {
        BoundaryStepProbe.beginRequest("outer", threadId());
        BoundaryStepProbe.onBfEnter(1.25f);
        BoundaryStepProbe.beginRequest("inner", threadId());
        BoundaryStepProbe.onBfNormalExit();
        BoundaryStepProbe.Snapshot old = BoundaryStepProbe.finishRequest();
        failed(old, "request-in-flight");
        eq("outer", old.requestId());
        eq(0x3fa00000, old.rawArgumentBits());
        BoundaryStepProbe.beginRequest("r2:ok", threadId());
        BoundaryStepProbe.onBfEnter(2.5f);
        BoundaryStepProbe.onBfNormalExit();
        BoundaryStepProbe.Snapshot next = BoundaryStepProbe.finishRequest();
        collected(next);
        eq("r2:ok", next.requestId());
        eq(0x40200000, next.rawArgumentBits());
        eq("outer", old.requestId());
        eq("request-in-flight", old.failureCode());
        eq(0x3fa00000, old.rawArgumentBits());
        eq(1, old.enterCount());
        eq(1, old.normalExitCount());
        System.out.println("J08 nested preserves old id/raw; snapshots immutable PASS");
    }

    private static void j09InvalidInputsAndUnscopedCallbacks() {
        String[] invalid = {null, "", "bad id", "bad/id", "bad\n"};
        for (String value : invalid) {
            BoundaryStepProbe.beginRequest(value, 0);
            BoundaryStepProbe.beginRequest("must-not-replace", threadId());
            BoundaryStepProbe.Snapshot s = BoundaryStepProbe.finishRequest();
            failed(s, "invalid-request-id");
            eq(value, s.requestId());
            noArgument(s);
        }
        for (long id : new long[]{0L, -1L}) {
            BoundaryStepProbe.beginRequest("bad-thread", id);
            failed(BoundaryStepProbe.finishRequest(), "invalid-thread-id");
        }
        BoundaryStepProbe.onBfEnter(9.0f);
        BoundaryStepProbe.onBfNormalExit();
        BoundaryStepProbe.Snapshot idle = BoundaryStepProbe.finishRequest();
        failed(idle, "no-active-request");
        eq(null, idle.requestId());
        eq(-1L, idle.expectedThreadId());
        eq(-1L, idle.observedThreadId());
        eq(0, idle.enterCount());
        eq(0, idle.normalExitCount());
        noArgument(idle);
        BoundaryStepProbe.beginRequest("after-idle", threadId());
        BoundaryStepProbe.onBfEnter(1.25f);
        BoundaryStepProbe.onBfNormalExit();
        collected(BoundaryStepProbe.finishRequest());
        BoundaryStepProbe.beginRequest("extra-normal", threadId());
        BoundaryStepProbe.onBfEnter(1.25f);
        BoundaryStepProbe.onBfNormalExit();
        BoundaryStepProbe.onBfNormalExit();
        BoundaryStepProbe.Snapshot extra = BoundaryStepProbe.finishRequest();
        failed(extra, "normal-count-mismatch");
        eq(1, extra.enterCount());
        eq(2, extra.normalExitCount());
        System.out.println("J09 invalid id/thread/priority/unscoped/extra normal PASS");
    }

    private static void noArgument(BoundaryStepProbe.Snapshot s) {
        eq(false, s.hasArgument());
        try {
            s.rawArgument();
            throw new AssertionError("expected rawArgument unavailable");
        } catch (IllegalStateException expected) { assertions++; }
        try {
            s.rawArgumentBits();
            throw new AssertionError("expected rawArgumentBits unavailable");
        } catch (IllegalStateException expected) { assertions++; }
    }

    private static void collected(BoundaryStepProbe.Snapshot s) {
        eq("COLLECTED", s.status());
        eq(null, s.failureCode());
    }

    private static void failed(BoundaryStepProbe.Snapshot s, String code) {
        eq("FAILED", s.status());
        eq(code, s.failureCode());
    }

    private static void eq(Object expected, Object actual) {
        assertions++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
