package com.rustedwarfare.reforge.android.metadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executable host contract tests; the OS and instrumentation transport are the only substitutes. */
public final class MetadataProbeHostTest {
    private static int assertions;
    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static final class Host implements MetadataProbe.Access, MetadataProbe.Transport {
        final List<String> calls = new ArrayList<String>();
        boolean valid;
        String failPath;
        Throwable injected = new IllegalStateException("permission root cause");
        int nonDirectoryIndex = -1;
        int badMode = 0100000;
        int nullAt = -1, negativeFieldsAt = -1;
        boolean directoriesOnly, linkReturnNull, failIdentity;
        String failLinkPath;
        int callsToStat;
        String identityFault;
        Map<String, String> bindings = bindings();
        String statusFailurePhase;
        final Throwable statusFailure = new IllegalStateException("status transport failure");
        boolean failFinish;
        int finishCount;
        final List<String> statuses = new ArrayList<String>();
        public Map<String, String> requestedBindings() { return bindings; }
        public void observeIdentity(MetadataProbe.Identity identity) throws Exception {
            identity.processUid = valid ? 10053 : 2000;
            identity.processPid = 123;
            identity.processName = valid ? "com.rustedwarfare.reforge.android" : "shell";
            identity.processUser0 = true;
            fill(identity.main, "com.rustedwarfare.reforge.android", 10053, "/data/app/main/base.apk", repeat("a", 64));
            fill(identity.test, "com.rustedwarfare.reforge.android.test", 10052, "/data/app/test/base.apk", repeat("b", 64));
            if ("uid".equals(identityFault)) identity.main.uid = 10052;
            if ("context".equals(identityFault)) identity.main.contextPackage = identity.test.packageName;
            if ("testuid".equals(identityFault)) identity.test.contextUid = 10053;
            if ("process".equals(identityFault)) identity.processName += ":other";
            if ("user".equals(identityFault)) identity.processUser0 = false;
            if ("split".equals(identityFault)) identity.main.splitCount = 1;
            if ("source".equals(identityFault)) identity.test.publicSourceDir = "/wrong.apk";
            if ("apk".equals(identityFault)) identity.main.apkSha256 = repeat("c", 64);
            if ("length".equals(identityFault)) identity.test.apkBytes++;
            if ("cert".equals(identityFault)) identity.test.certificateSha256 = repeat("d", 64);
            if ("der".equals(identityFault)) identity.test.certificateDerHex = "bbbb";
            if ("signers".equals(identityFault)) identity.main.signerCount = 2;
            if (failIdentity) throw new IllegalArgumentException("identity observation interrupted");
        }
        public MetadataProbe.Stat lstat(String path) throws Exception {
            calls.add("lstat:" + path);
            if (path.equals(failPath)) throw new MetadataProbe.PlatformFailure(13, injected);
            int index = callsToStat++;
            if (index == nullAt) return null;
            if (index == negativeFieldsAt) return new MetadataProbe.Stat(0040000, 0, 0, 10, 20, -1, 40, 50);
            return new MetadataProbe.Stat(index == nonDirectoryIndex ? badMode :
                    path.equals("/storage") || directoriesOnly ? 0040000 : 0120000);
        }
        public String readlink(String path) throws Exception {
            calls.add("readlink:" + path);
            if (path.equals(failLinkPath)) throw new MetadataProbe.PlatformFailure(22, injected);
            return linkReturnNull ? null : "/never-consume/../private\n\"raw\"";
        }
        public long utcMillis() { return 1000; }
        public long monotonicNanos() { return 2000; }
        public void status(String phase, MetadataProbe.Result result) {
            statuses.add(phase);
            if (phase.equals(statusFailurePhase)) throw (RuntimeException) statusFailure;
        }
        public void finish(MetadataProbe.Result result) { finishCount++; if (failFinish) throw new IllegalStateException("finish transport failure"); }
    }

    private static String repeat(String value, int count) { StringBuilder out = new StringBuilder(); for (int i = 0; i < count; i++) out.append(value); return out.toString(); }
    private static Map<String, String> bindings() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("bindingId", "host-fixture");
        map.put("mainApkSha256", repeat("a", 64)); map.put("mainApkBytes", "1234");
        map.put("testApkSha256", repeat("b", 64)); map.put("testApkBytes", "1234");
        map.put("certificateSha256", repeat("e", 64)); map.put("certificateBytes", "2");
        return map;
    }
    private static void fill(MetadataProbe.PackageIdentity p, String name, int uid, String path, String hash) {
        p.contextPackage = name; p.packageName = name; p.processName = name;
        p.contextUid = uid; p.uid = uid; p.sourceDir = path; p.publicSourceDir = path;
        p.signerCount = 1; p.apkSha256 = hash; p.apkBytes = 1234;
        p.certificateSha256 = repeat("e", 64); p.certificateBytes = 2; p.certificateDerHex = "aaaa";
    }

    public static void main(String[] args) {
        if (args[0].equals("all") || args[0].equals("identity")) {
            Host host = new Host();
            MetadataProbe.Result result = MetadataProbe.run(host, host);
            check(result.primaryFailure != null, "wrong actual identity must reject");
            check(result.identity.processUid == 2000, "record the actual observed UID");
            check(host.calls.isEmpty(), "identity refusal must issue zero path queries");
        }
        if (args[0].equals("all") || args[0].equals("sequence")) {
            Host host = new Host(); host.valid = true;
            MetadataProbe.Result result = MetadataProbe.run(host, host);
            check(result.completed && result.primaryFailure == null, "three legal stats and two links must finish");
            check(host.calls.equals(Arrays.asList("lstat:/storage", "lstat:/storage/self", "lstat:/storage/self/primary",
                    "readlink:/storage/self", "readlink:/storage/self/primary")), "fixed non-recursive order only");
            check(result.paths.equals(Arrays.asList("/storage", "/storage/self", "/storage/self/primary")), "retain queried paths");
            check(result.links.size() == 2 && result.links.get("/storage/self").equals("/never-consume/../private\n\"raw\""),
                    "retain raw link text without consuming it");
        }
        if (args[0].equals("all") || args[0].equals("failures")) {
            Host host = new Host(); host.valid = true; host.failPath = "/storage/self";
            MetadataProbe.Result result = MetadataProbe.run(host, host);
            check(result.primaryFailure != null && result.primaryFailure.getCause() == host.injected, "preserve first OS cause");
            check("lstat".equals(result.primaryStage) && "/storage/self".equals(result.primaryPath) &&
                    Integer.valueOf(13).equals(result.primaryErrno), "retain exact errno/path/stage");
            check(host.calls.equals(Arrays.asList("lstat:/storage", "lstat:/storage/self")), "first errno stops remaining queries");
            check(result.stats.size() == 1 && result.stats.get("/storage").inode == 20 && !result.completed,
                    "retain prior successful stat fields");
            for (int index = 0; index < 3; index++) {
                host = new Host(); host.valid = true; host.nonDirectoryIndex = index;
                result = MetadataProbe.run(host, host);
                check(result.primaryFailure != null && !result.completed, "regular or unknown type rejected at " + index);
                check(host.calls.size() == index + 1 && result.stats.size() == index + 1, "reject type stops with observed partial");
            }
        }
        if (args[0].equals("all") || args[0].equals("bindings")) {
            for (String fault : Arrays.asList("uid", "context", "testuid", "process", "user", "split", "source", "apk", "length", "cert", "der", "signers")) {
                Host host = new Host(); host.valid = true; host.identityFault = fault;
                MetadataProbe.Result result = MetadataProbe.run(host, host);
                check(result.primaryFailure != null && host.calls.isEmpty(), "reject actual installation mismatch before path query: " + fault);
            }
            for (String field : Arrays.asList("unexpectedPath", "mainApkSha256", "testApkBytes", "certificateBytes", "bindingId")) {
                Host host = new Host(); host.valid = true;
                host.bindings.put(field, field.equals("unexpectedPath") ? "/private/data" : "bad/path");
                MetadataProbe.Result result = MetadataProbe.run(host, host);
                check(result.primaryFailure != null && host.calls.isEmpty(), "reject malformed request: " + field);
            }
        }
        if (args[0].equals("all") || args[0].equals("transport")) {
            Host host = new Host(); host.valid = true; host.statusFailurePhase = "identity";
            MetadataProbe.Result result = MetadataProbe.run(host, host);
            check(result.primaryFailure == null && result.transportFailures.size() == 1 && !result.successful(), "identity delivery failure remains separate");
            check(host.calls.isEmpty() && host.finishCount == 1, "identity delivery failure stops paths but still attempts final");
            check(result.transportFailures.get(0).failure == host.statusFailure && result.metadataProbeTargetProcessExecuted,
                    "retain transport throwable and actual execution fact");
            host = new Host(); host.valid = true; host.failPath = "/storage/self"; host.statusFailurePhase = "failure"; host.failFinish = true;
            result = MetadataProbe.run(host, host);
            check(result.primaryFailure != null && result.primaryFailure.getCause() == host.injected && result.primaryErrno == 13,
                    "status and finish failure must not replace first errno");
            check(result.transportFailures.size() == 2 && host.finishCount == 1 && result.stats.size() == 1,
                    "retain independent failure delivery and finish errors with partial");
            host = new Host(); host.valid = true; host.statusFailurePhase = "lstat:/storage/self";
            result = MetadataProbe.run(host, host);
            check(host.calls.equals(Arrays.asList("lstat:/storage", "lstat:/storage/self")) && result.primaryFailure == null &&
                    result.transportFailures.size() == 1 && !result.completed, "stop when partial observation cannot be delivered");
            check(result.startUtcMillis == 1000 && result.endUtcMillis == 1000 && result.startMonotonicNanos == 2000 &&
                    result.endMonotonicNanos == 2000, "preserve actual clock samples");
        }
        if (args[0].equals("all") || args[0].equals("json")) {
            Host host = new Host(); host.valid = true;
            MetadataProbe.Result result = MetadataProbe.run(host, host);
            String encoded = result.toJson();
            check(encoded.contains("\"oldRunsProtected\":false") && encoded.contains("\"applicationExecutedForProduct\":false"),
                    "metadata success must preserve protection/product boundaries");
            check(encoded.contains("\\n\\\"raw\\\""), "JSON must escape raw newline and quote in link text");
            System.out.println("JSON_SUCCESS " + encoded);
            host = new Host(); host.valid = true; host.failPath = "/storage/self"; host.statusFailurePhase = "failure";
            result = MetadataProbe.run(host, host);
            check(result.toJson().contains("permission root cause") && result.toJson().contains("status transport failure"),
                    "retain primary cause and transport trace independently");
            System.out.println("JSON_FAILURE " + result.toJson());
        }
        if (args[0].equals("all") || args[0].equals("boundaries")) {
            Host host = new Host(); host.valid = true; host.directoriesOnly = true;
            MetadataProbe.Result result = MetadataProbe.run(host, host);
            check(result.successful() && host.calls.equals(Arrays.asList("lstat:/storage", "lstat:/storage/self", "lstat:/storage/self/primary")),
                    "directory observations never invoke readlink");
            for (int mode : new int[] {0120000, 0010000, 0}) {
                host = new Host(); host.valid = true; host.nonDirectoryIndex = 0; host.badMode = mode;
                result = MetadataProbe.run(host, host);
                check(result.primaryFailure != null && host.calls.size() == 1 && result.stats.get("/storage").mode == mode,
                        "/storage must be a real directory, retaining unsupported raw mode " + mode);
            }
            host = new Host(); host.valid = true; host.nullAt = 1;
            result = MetadataProbe.run(host, host);
            check(result.primaryFailure != null && host.calls.size() == 2 && result.stats.containsKey("/storage/self"),
                    "missing stat fields fail after retaining prior partial");
            host = new Host(); host.valid = true; host.negativeFieldsAt = 1;
            result = MetadataProbe.run(host, host);
            check(result.primaryFailure != null && result.stats.get("/storage/self").size == -1 && host.calls.size() == 2,
                    "invalid fixed fields stop without discarding the observation");
            for (String path : Arrays.asList("/storage/self", "/storage/self/primary")) {
                host = new Host(); host.valid = true; host.failLinkPath = path;
                result = MetadataProbe.run(host, host);
                check(result.primaryErrno == 22 && path.equals(result.primaryPath) && "readlink".equals(result.primaryStage),
                        "preserve first readlink errno at " + path);
                check(result.stats.size() == 3 && result.links.size() == (path.equals("/storage/self") ? 0 : 1) && !result.completed,
                        "preserve stat and earlier link partial without retries");
            }
            host = new Host(); host.valid = true; host.linkReturnNull = true;
            result = MetadataProbe.run(host, host);
            check(result.primaryFailure != null && host.calls.size() == 4 && result.links.isEmpty(), "null link text is a failure, never an absent target");
            host = new Host(); host.valid = true; host.failFinish = true;
            result = MetadataProbe.run(host, host);
            check(result.completed && !result.successful() && host.finishCount == 1 && host.statuses.contains("finish-failure"),
                    "finish failure gets one status fallback, no retry or primary replacement");
            host = new Host(); host.valid = true; host.failIdentity = true;
            result = MetadataProbe.run(host, host);
            check(result.identityObservationStarted && !result.identityObservationCompleted && host.calls.isEmpty() &&
                    result.metadataProbeTargetProcessExecuted && result.primaryFailure.getMessage().equals("identity observation interrupted"),
                    "interrupted identity observation retains real partial and failure");
            for (String value : Arrays.asList("0", "-1", "9223372036854775808")) {
                host = new Host(); host.valid = true; host.bindings.put("mainApkBytes", value);
                result = MetadataProbe.run(host, host);
                check(result.primaryFailure != null && !result.identityObservationStarted && host.calls.isEmpty(), "reject invalid size before observation: " + value);
            }
            host = new Host(); host.valid = true; host.bindings.remove("certificateBytes");
            result = MetadataProbe.run(host, host);
            check(result.primaryFailure != null && host.calls.isEmpty(), "missing binding cannot proceed");
        }
        System.out.println("PASS " + args[0] + ": " + assertions + " checks");
    }
}
