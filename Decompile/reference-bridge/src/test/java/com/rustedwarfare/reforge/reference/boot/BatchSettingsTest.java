package com.rustedwarfare.reforge.reference.boot;

import com.rustedwarfare.reforge.reference.io.FatalLatch;

/** Cold JVM namespace and first-fatal checks; no Android storage claim. */
public final class BatchSettingsTest {
    private static final String RUN = "ab70419e-d99a-4c18-b386-83e3d6d19283";
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if ("concurrent".equals(mode)) {
            final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicReference<Throwable> rejected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread[] threads = new Thread[2];
            String[] runs = {RUN, "00000000-0000-4000-8000-000000000001"};
            for (int i = 0; i < 2; i++) {
                final String candidate = runs[i];
                threads[i] = new Thread(() -> {
                    try { gate.await(); BatchSettings.configure(candidate); accepted.incrementAndGet(); }
                    catch (Throwable e) { rejected.set(e); }
                });
                threads[i].start();
            }
            gate.countDown();
            for (Thread thread : threads) thread.join();
            if (accepted.get() != 1 || !(rejected.get() instanceof FatalLatch.Failure)
                    || fail(() -> BatchSettings.name()) != rejected.get())
                throw new AssertionError("concurrent namespace binding not atomic");
        } else if ("valid".equals(mode)) {
            BatchSettings.configure(RUN);
            BatchSettings.configure(RUN);
            String expected = "rwr_reference_ab70419ed99a4c18b38683e3d6d19283";
            if (!expected.equals(BatchSettings.name())
                    || !(expected+".xml").equals(BatchSettings.xmlName())
                    || !(expected+".xml.bak").equals(BatchSettings.backupName()))
                throw new AssertionError("namespace differs");
        } else if ("changed".equals(mode)) {
            BatchSettings.configure(RUN);
            FatalLatch.Failure first = fail(() -> BatchSettings.configure("00000000-0000-4000-8000-000000000001"));
            if (fail(() -> BatchSettings.configure(RUN)) != first
                    || fail(() -> BatchSettings.name()) != first)
                throw new AssertionError("failed configuration resumed");
        } else if ("prior".equals(mode)) {
            FatalLatch.Failure first;
            try { GbootBootstrap.requireStateDecoded(false); throw new AssertionError("missing fatal"); }
            catch (FatalLatch.Failure e) { first = e; }
            if (fail(() -> BatchSettings.name()) != first) throw new AssertionError("first fatal changed");
        } else if ("missing".equals(mode)) {
            fail(() -> BatchSettings.name());
        } else {
            String bad = "null".equals(mode) ? null : mode;
            fail(() -> BatchSettings.configure(bad));
        }
        System.out.println("PASS BatchSettings " + mode);
    }
    private interface Action { void run(); }
    private static FatalLatch.Failure fail(Action action) {
        try { action.run(); throw new AssertionError("accepted invalid namespace"); }
        catch (FatalLatch.Failure e) { return e; }
    }
}
