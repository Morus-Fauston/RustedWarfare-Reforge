package com.rustedwarfare.reforge.reference.instrumentation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

public final class TrialFailureTest {
    private static int checks;
    public static void main(String[] args) throws Throwable {
        final Throwable load = new IllegalStateException("original-load-first");
        final Throwable cleanup = new TimeoutException("cleanup-timeout");
        final int[] calls = {0};
        TrialLifecycle state = new TrialLifecycle();
        state.execute(() -> { state.stage = "native-load"; throw load; }, () -> { calls[0]++; throw cleanup; });
        check(calls[0] == 1, "load failure still cleans once");
        check(state.primary == load && "native-load".equals(state.primaryStage), "primary identity and stage preserved");
        check(state.cleanupErrors.size() == 1 && state.cleanupErrors.get(0) == cleanup, "cleanup actual timeout preserved");
        final Throwable transport = new IllegalStateException("send-failed");
        state.attemptFailureTransport(() -> { throw transport; });
        check(state.transportErrors.size() == 1 && state.transportErrors.get(0) == transport && state.cleanupErrors.size() == 1, "transport separate from cleanup");
        TrialLifecycle normal = new TrialLifecycle();
        normal.execute(() -> {}, () -> { throw cleanup; });
        check(normal.primary == cleanup && normal.cleanupErrors.get(0) == cleanup && "cleanup".equals(normal.primaryStage), "cleanup-only failure cannot succeed");
        Map<String, Object> partial = ReadbackEvidence.map(); partial.put("actualHash", "unexpected-real-hash");
        state.observe("identity", partial);
        check(state.pending.get("identity") == partial, "partial observation retained without fake normal stage");
        Map<String, Object> fields = ReadbackEvidence.map();
        Throwable fieldFailure = new NoSuchFieldException("actual-field-missing");
        Throwable captured = ReadbackEvidence.capture(fields, () -> { fields.put("actualMapWidth", 30); throw fieldFailure; });
        check(captured == fieldFailure && Integer.valueOf(30).equals(fields.get("actualMapWidth"))
                && Boolean.FALSE.equals(fields.get("complete")), "partial fields and original read error retained");
        final int[] readbacks = {0};
        TrialLifecycle factoryState = new TrialLifecycle();
        factoryState.execute(() -> {
            factoryState.stage = "factory";
            factoryState.runObserved(() -> { factoryState.observe("factory", partial); throw load; },
                    () -> { readbacks[0]++; throw fieldFailure; });
        }, () -> ReadbackEvidence.map());
        check(readbacks[0] == 1 && factoryState.primary == load && "factory".equals(factoryState.primaryStage)
                && factoryState.readbackErrors.get(0) == fieldFailure, "factory mid-failure attempts readback but preserves first");
        TrialLifecycle lateLoad = new TrialLifecycle();
        lateLoad.execute(() -> { lateLoad.runObserved(() -> {}, () -> lateLoad.observe("native-pre-spawn", partial)); throw new TimeoutException("load returned late"); }, () -> ReadbackEvidence.map());
        check(lateLoad.pending.get("native-pre-spawn") == partial && lateLoad.primary instanceof TimeoutException, "late load retains initial observation before deadline failure");
        final Object neutral = new Object(), other = new Object();
        Map<String, Object> relations = ReadbackEvidence.relations(neutral, new Object[] {other, neutral, null, neutral}, neutral);
        check(relations.get("matchingSlots").toString().equals("[1, 3]") && Boolean.TRUE.equals(relations.get("isBp")), "neutral actual slot and bp identity");
        check(!ReadbackEvidence.same(new String("equal"), new String("equal")), "same fields are not same definition instance");
        check(ReadbackEvidence.relations(null, new Object[] {null}, null) == null, "null relation stays null");
        Path fixture = Paths.get(args[0]).toRealPath();
        Path file = fixture.resolve("wrong-seed.bin"); Files.write(file, new byte[] {1, 2, 3});
        List<Object> evidence = new ArrayList<Object>();
        try { ReadbackFiles.file(file, evidence, 3L, "0000000000000000000000000000000000000000000000000000000000000000"); throw new AssertionError("wrong seed accepted"); }
        catch (IllegalArgumentException expected) { }
        Map<?, ?> observed = (Map<?, ?>) evidence.get(0);
        check(Long.valueOf(3L).equals(observed.get("bytes")) && InitialProtocol.sha256(new byte[] {1, 2, 3}).equals(observed.get("sha256")), "wrong seed actual bytes and hash retained");
        Path absent = fixture.resolve("missing"); evidence.clear();
        try { ReadbackFiles.file(absent, evidence, null, null); throw new AssertionError("missing file accepted"); }
        catch (java.io.IOException expected) { }
        check(evidence.size() == 1 && absent.toString().equals(((Map<?, ?>) evidence.get(0)).get("literal")), "read failure keeps literal observation");
        Path link = Paths.get(args[1]); evidence.clear();
        try { ReadbackFiles.anchor(link, evidence); throw new AssertionError("linked literal root accepted"); }
        catch (IllegalArgumentException expected) { }
        Map<?, ?> linked = (Map<?, ?>) evidence.get(0);
        check(link.toString().equals(linked.get("literal")) && linked.get("canonical") != null, "linked root records literal and canonical before rejection");
        Path trust = fixture.resolve("trusted"); Files.createDirectory(trust); evidence.clear();
        Path child = trust.resolve("reference-test");
        Files.createSymbolicLink(child, fixture);
        try { ReadbackFiles.fixedParents(trust, child, evidence); throw new AssertionError("linked private child accepted"); }
        catch (IllegalArgumentException expected) { }
        check(!evidence.isEmpty() && child.toString().equals(((Map<?, ?>) evidence.get(0)).get("literal")), "fixed private chain link observed and rejected");
        System.out.println("PASS TrialFailure checks=" + checks + " real-files-and-links");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
