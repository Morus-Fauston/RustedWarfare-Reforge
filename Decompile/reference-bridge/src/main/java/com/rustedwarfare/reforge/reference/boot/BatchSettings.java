package com.rustedwarfare.reforge.reference.boot;

/** Per-batch settings namespace; never reuses or clears old preferences. */
public final class BatchSettings {
    private static String run;
    private static String settings;
    private static RuntimeException failure;
    private BatchSettings() { }
    public static synchronized void configure(String runId) {
        if (failure != null) throw failure;
        if (runId == null || !runId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || (run != null && !run.equals(runId)))
            throw reject();
        if (run == null) {
            run = runId;
            settings = "rwr_reference_" + runId.replace("-", "");
        }
    }
    public static synchronized String name() {
        if (failure != null) throw failure;
        if (settings == null) throw reject();
        return settings;
    }
    public static String xmlName() { return name() + ".xml"; }
    public static String backupName() { return name() + ".xml.bak"; }
    private static RuntimeException reject() {
        try {
            throw GbootBootstrap.unsupportedRouteFailure("settings-batch-identity");
        } catch (RuntimeException first) {
            failure = first;
            return first;
        }
    }
}
