package com.rustedwarfare.reforge.android.metadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed, non-recursive metadata diagnostic. The Android adapter owns the public OS APIs. */
public final class MetadataProbe {
    public static final String MAIN = "com.rustedwarfare.reforge.android";
    public static final String TEST = MAIN + ".test";
    private MetadataProbe() { }
    // Finite observed User0 installs, not a general UID input or a proof of device identity.
    // The host must still bind its designated instance, exact installed UID pair and APK/certificate.
    private static boolean knownMainUid(int uid) { return uid == 10053 || uid == 10051; }

    public interface Access {
        Map<String, String> requestedBindings() throws Exception;
        void observeIdentity(Identity identity) throws Exception;
        Stat lstat(String path) throws Exception;
        String readlink(String path) throws Exception;
        long utcMillis();
        long monotonicNanos();
    }

    public interface Transport {
        void status(String phase, Result result) throws Exception;
        void finish(Result result) throws Exception;
    }

    public static final class Identity {
        public int processUid;
        public int processPid;
        public String processName;
        public boolean processUser0;
        public int sdkInt;
        public final PackageIdentity main = new PackageIdentity();
        public final PackageIdentity test = new PackageIdentity();

        public void requireRuntime() {
            require(knownMainUid(processUid) && processPid > 0 && MAIN.equals(processName) && processUser0,
                    "Actual target process identity mismatch");
            main.requireRuntime(MAIN, processUid);
            test.requireRuntime(TEST, 10052);
            require(!main.sourceDir.equals(test.sourceDir), "MAIN and TEST must have distinct code APKs");
        }
    }

    public static final class PackageIdentity {
        public String contextPackage, packageName, processName, sourceDir, publicSourceDir;
        public int contextUid, uid, splitCount, signerCount;
        public boolean multipleSigners;
        public String apkSha256, certificateSha256, certificateDerHex;
        public long apkBytes, certificateBytes;

        private void requireRuntime(String name, int expectedUid) {
            require(name.equals(contextPackage) && name.equals(packageName) && name.equals(processName) &&
                    contextUid == expectedUid && uid == expectedUid, "Context/PM package or UID mismatch: " + name);
            require(splitCount == 0 && sourceDir != null && sourceDir.startsWith("/") &&
                    sourceDir.endsWith("/base.apk") && sourceDir.indexOf('\0') < 0 &&
                    !sourceDir.contains("/../") && !sourceDir.contains("/./") &&
                    sourceDir.equals(publicSourceDir), "Expected one PM base code APK: " + name);
        }
    }

    public static final class Bindings {
        public final String bindingId, mainApkSha256, testApkSha256, certificateSha256;
        public final long mainApkBytes, testApkBytes, certificateBytes;

        private Bindings(Map<String, String> request) {
            require(request != null && request.keySet().equals(new HashSet<String>(Arrays.asList(
                    "bindingId", "mainApkSha256", "mainApkBytes", "testApkSha256", "testApkBytes",
                    "certificateSha256", "certificateBytes"))), "Expected exact seven identity binding keys");
            bindingId = request.get("bindingId");
            require(bindingId != null && bindingId.matches("[A-Za-z0-9._-]{1,96}"), "Invalid bindingId");
            mainApkSha256 = hash(request.get("mainApkSha256"));
            testApkSha256 = hash(request.get("testApkSha256"));
            certificateSha256 = hash(request.get("certificateSha256"));
            mainApkBytes = length(request.get("mainApkBytes"));
            testApkBytes = length(request.get("testApkBytes"));
            certificateBytes = length(request.get("certificateBytes"));
        }

        private static String hash(String value) {
            require(value != null && value.matches("[0-9a-f]{64}"), "Expected lowercase full SHA256");
            return value;
        }

        private static long length(String value) {
            require(value != null && value.matches("[1-9][0-9]*"), "Expected positive decimal byte count");
            return Long.parseLong(value);
        }

        private void verify(Identity actual) {
            actual.requireRuntime();
            verifyPackage(actual.main, mainApkSha256, mainApkBytes);
            verifyPackage(actual.test, testApkSha256, testApkBytes);
            require(actual.main.certificateDerHex.equals(actual.test.certificateDerHex),
                    "MAIN/TEST complete signer DER differs");
        }

        private void verifyPackage(PackageIdentity actual, String hash, long bytes) {
            require(hash.equals(actual.apkSha256) && bytes == actual.apkBytes,
                    "Observed complete code APK differs: " + actual.packageName);
            require(actual.signerCount == 1 && !actual.multipleSigners &&
                    certificateSha256.equals(actual.certificateSha256) && certificateBytes == actual.certificateBytes &&
                    actual.certificateDerHex != null && actual.certificateDerHex.matches("[0-9a-f]+") &&
                    actual.certificateDerHex.length() == certificateBytes * 2,
                    "Observed complete signer DER differs: " + actual.packageName);
        }
    }

    public static final class Stat {
        public final int mode;
        public final int uid, gid;
        public final long device, inode, size, modifiedSeconds, changedSeconds;
        public Stat(int mode) { this(mode, 0, 0, 10, 20, 30, 40, 50); }
        public Stat(int mode, int uid, int gid, long device, long inode, long size,
                    long modifiedSeconds, long changedSeconds) {
            this.mode = mode; this.uid = uid; this.gid = gid; this.device = device;
            this.inode = inode; this.size = size; this.modifiedSeconds = modifiedSeconds;
            this.changedSeconds = changedSeconds;
        }
    }

    public static final class PlatformFailure extends Exception {
        public final int errno;
        public PlatformFailure(int errno, Throwable cause) {
            super(cause == null ? "OS failure" : cause.getMessage(), cause);
            this.errno = errno;
        }
    }

    public static final class Result {
        public final Identity identity = new Identity();
        public final List<String> paths = new ArrayList<String>();
        public final Map<String, String> links = new LinkedHashMap<String, String>();
        public final Map<String, Stat> stats = new LinkedHashMap<String, Stat>();
        public boolean completed;
        public Throwable primaryFailure;
        public String primaryStage, primaryPath;
        public Integer primaryErrno;
        public Bindings expected;
        public final List<DeliveryFailure> transportFailures = new ArrayList<DeliveryFailure>();
        public final List<Throwable> secondaryFailures = new ArrayList<Throwable>();
        public long startUtcMillis, endUtcMillis, startMonotonicNanos, endMonotonicNanos;
        public boolean metadataProbeTargetProcessExecuted;
        public boolean identityObservationStarted, identityObservationCompleted;
        public boolean successful() { return completed && primaryFailure == null && transportFailures.isEmpty() && secondaryFailures.isEmpty(); }
        public String toJson() { return MetadataJson.encode(this); }
    }

    public static final class DeliveryFailure {
        public final String phase;
        public final Throwable failure;
        DeliveryFailure(String phase, Throwable failure) { this.phase = phase; this.failure = failure; }
    }

    public static Result run(Access access, Transport transport) {
        Result result = new Result();
        String stage = "clock-start", path = null;
        try {
            result.startUtcMillis = access.utcMillis();
            result.startMonotonicNanos = access.monotonicNanos();
            stage = "binding";
            result.expected = new Bindings(access.requestedBindings());
            stage = "identity";
            result.identityObservationStarted = true;
            access.observeIdentity(result.identity);
            result.identityObservationCompleted = true;
            result.expected.verify(result.identity);
            result.metadataProbeTargetProcessExecuted = true;
            if (!status(transport, "identity", result)) return result;
            String[] paths = {"/storage", "/storage/self", "/storage/self/primary"};
            Stat[] stats = new Stat[3];
            for (int index = 0; index < paths.length; index++) {
                stage = "lstat"; path = paths[index];
                stats[index] = access.lstat(paths[index]);
                result.paths.add(paths[index]);
                result.stats.put(paths[index], stats[index]);
                Stat stat = stats[index];
                if (stat == null || stat.mode < 0 || stat.uid < 0 || stat.gid < 0 || stat.size < 0) {
                    throw new IllegalStateException("Missing or invalid fixed stat fields");
                }
                int type = stat.mode & 0170000;
                if (type != 0040000 && !(index > 0 && type == 0120000)) {
                    throw new IllegalStateException("Unexpected fixed-path entry type: " + type);
                }
                if (!status(transport, "lstat:" + path, result)) return result;
            }
            for (int index = 1; index < paths.length; index++) {
                if ((stats[index].mode & 0170000) == 0120000) {
                    stage = "readlink"; path = paths[index];
                    String text = access.readlink(paths[index]);
                    if (text == null) throw new IllegalStateException("Missing link text");
                    result.links.put(paths[index], text);
                    if (!status(transport, "readlink:" + path, result)) return result;
                }
            }
            result.completed = true;
        } catch (Throwable failure) {
            result.primaryFailure = failure;
            result.primaryStage = stage;
            result.primaryPath = path;
            if (failure instanceof PlatformFailure) result.primaryErrno = ((PlatformFailure) failure).errno;
        } finally {
            // An observed target UID/name/Context remains a fact even when later install checks reject.
            Identity observed = result.identity;
            result.metadataProbeTargetProcessExecuted = knownMainUid(observed.processUid) && observed.processPid > 0 &&
                    MAIN.equals(observed.processName) && observed.processUser0 && MAIN.equals(observed.main.contextPackage) &&
                    observed.main.contextUid == observed.processUid;
            try {
                result.endUtcMillis = access.utcMillis();
                result.endMonotonicNanos = access.monotonicNanos();
            } catch (Throwable failure) {
                if (result.primaryFailure == null) {
                    result.primaryFailure = failure;
                    result.primaryStage = "clock-end";
                } else result.secondaryFailures.add(failure);
            }
            if (result.primaryFailure != null) status(transport, "failure", result);
            try { transport.finish(result); }
            catch (Throwable failure) {
                result.transportFailures.add(new DeliveryFailure("finish", failure));
                status(transport, "finish-failure", result);
            }
        }
        return result;
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    private static boolean status(Transport transport, String phase, Result result) {
        try { transport.status(phase, result); return true; }
        catch (Throwable failure) {
            result.transportFailures.add(new DeliveryFailure("status:" + phase, failure));
            return false;
        }
    }
}
