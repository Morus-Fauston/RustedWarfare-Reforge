package com.rustedwarfare.reforge.reference.instrumentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One attempt's first error, partial observations, and independent cleanup/transport errors. */
final class TrialLifecycle {
    interface Action { void run() throws Throwable; }
    interface Cleanup { Map<String, Object> run() throws Throwable; }
    String stage = "request";
    String primaryStage;
    Throwable primary;
    boolean operationFailed;
    final Map<String, Object> pending = ReadbackEvidence.map();
    final List<Throwable> cleanupErrors = new ArrayList<Throwable>();
    final List<Throwable> transportErrors = new ArrayList<Throwable>();
    final List<Throwable> readbackErrors = new ArrayList<Throwable>();
    Map<String, Object> cleanupOutcome;

    void observe(String name, Map<String, Object> observation) { pending.put(name, observation); }
    void emitted(String name) { pending.remove(name); }

    static void requireLoadBudget(long now, long loadStarted, long sessionStarted) {
        InitialProtocol.require(now >= loadStarted && now - loadStarted <= 25000000000L, "native-load-deadline");
        InitialProtocol.require(now >= sessionStarted && now - sessionStarted <= 80000000000L, "session-deadline:native-load");
    }

    static void prepareThenLoad(Action checkBudget, Action prepare, Action load) throws Throwable {
        checkBudget.run(); prepare.run(); checkBudget.run(); load.run();
    }

    static void cleanupActions(List<Throwable> failures, Action... actions) {
        for (Action action : actions) {
            try { action.run(); } catch (Throwable error) { failures.add(error); }
        }
    }

    void execute(Action work, Cleanup cleanup) {
        try { work.run(); }
        catch (Throwable error) { primary = error; primaryStage = stage; }
        finally {
            stage = "cleanup";
            cleanupOutcome = ReadbackEvidence.map(); cleanupOutcome.put("attempted", true); cleanupOutcome.put("completed", false);
            try { cleanupOutcome = cleanup.run(); }
            catch (Throwable error) {
                cleanupErrors.add(error);
                if (primary == null) { primary = error; primaryStage = "cleanup"; }
            }
        }
    }

    void runObserved(Action operation, Action readback) throws Throwable {
        Throwable first = null; String failedAt = null;
        try { operation.run(); }
        catch (Throwable error) { first = error; failedAt = stage; }
        operationFailed = first != null;
        try { readback.run(); }
        catch (Throwable error) {
            if (first == null) { first = error; failedAt = stage; }
            else readbackErrors.add(error);
        } finally { operationFailed = false; }
        if (first != null) { stage = failedAt; throw first; }
    }

    void transport(Action output) throws Throwable {
        try { output.run(); }
        catch (Throwable error) { transportErrors.add(error); throw error; }
    }

    void attemptFailureTransport(Action output) {
        try { transport(output); } catch (Throwable recorded) { }
    }
}
