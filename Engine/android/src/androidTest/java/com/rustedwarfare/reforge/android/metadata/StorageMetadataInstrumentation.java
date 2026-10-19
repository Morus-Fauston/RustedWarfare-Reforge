package com.rustedwarfare.reforge.android.metadata;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;

import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Independent metadata entry; preserves normal Application/provider binding and the old runner. */
public final class StorageMetadataInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        MetadataProbe.run(new AndroidAccess(), new MetadataProbe.Transport() {
            @Override public void status(String phase, MetadataProbe.Result result) {
                sendStatus(1, output(phase, result));
            }
            @Override public void finish(MetadataProbe.Result result) {
                StorageMetadataInstrumentation.this.finish(result.successful() ? Activity.RESULT_OK : Activity.RESULT_CANCELED,
                        output("final", result));
            }
        });
    }

    private static Bundle output(String phase, MetadataProbe.Result result) {
        Bundle bundle = new Bundle();
        bundle.putString("phase", phase);
        bundle.putString("metadata", result.toJson());
        bundle.putString("result", result.successful() ? "PASS: fixed storage metadata only" : "INCOMPLETE/FAIL: inspect metadata");
        return bundle;
    }

    private final class AndroidAccess implements MetadataProbe.Access {
        @Override public Map<String, String> requestedBindings() {
            Map<String, String> result = new LinkedHashMap<String, String>();
            if (arguments == null) return result;
            for (String key : arguments.keySet()) {
                Object value = arguments.get(key);
                if (!(value instanceof String)) throw new IllegalArgumentException("Binding must be a string: " + key);
                result.put(key, (String) value);
            }
            return result;
        }

        @Override public void observeIdentity(MetadataProbe.Identity actual) throws Exception {
            actual.processUid = Process.myUid();
            actual.processPid = Process.myPid();
            actual.processUser0 = Process.myUserHandle().equals(UserHandle.getUserHandleForUid(0));
            actual.sdkInt = Build.VERSION.SDK_INT;
            if (actual.sdkInt < 28) throw new IllegalStateException("Public process-name/signing APIs require API28+");
            actual.processName = Application.getProcessName();
            PackageInfo main = observePackage(getTargetContext(), MetadataProbe.MAIN, actual.main);
            PackageInfo test = observePackage(getContext(), MetadataProbe.TEST, actual.test);
            // Reject malformed Context/PM identity before the explicit, code-APK-only content exception.
            actual.requireRuntime();
            observeCode(main, actual.main);
            observeCode(test, actual.test);
        }

        private PackageInfo observePackage(Context context, String fixedName, MetadataProbe.PackageIdentity actual)
                throws PackageManager.NameNotFoundException {
            if (context == null) throw new IllegalStateException("Missing instrumentation Context");
            actual.contextPackage = context.getPackageName();
            ApplicationInfo contextInfo = context.getApplicationInfo();
            if (contextInfo == null) throw new IllegalStateException("Missing Context ApplicationInfo");
            actual.contextUid = contextInfo.uid;
            PackageInfo info = context.getPackageManager().getPackageInfo(fixedName, PackageManager.GET_SIGNING_CERTIFICATES);
            actual.packageName = info.packageName;
            ApplicationInfo app = info.applicationInfo;
            if (app == null) throw new IllegalStateException("Missing PM ApplicationInfo");
            actual.uid = app.uid;
            actual.processName = app.processName;
            actual.sourceDir = app.sourceDir;
            actual.publicSourceDir = app.publicSourceDir;
            actual.splitCount = Math.max(info.splitNames == null ? 0 : info.splitNames.length,
                    Math.max(app.splitSourceDirs == null ? 0 : app.splitSourceDirs.length,
                            app.splitPublicSourceDirs == null ? 0 : app.splitPublicSourceDirs.length));
            return info;
        }

        private void observeCode(PackageInfo info, MetadataProbe.PackageIdentity actual) throws Exception {
            if (info.signingInfo == null) throw new IllegalStateException("Missing PM SigningInfo");
            actual.multipleSigners = info.signingInfo.hasMultipleSigners();
            Signature[] signers = info.signingInfo.getApkContentsSigners();
            actual.signerCount = signers == null ? 0 : signers.length;
            if (actual.multipleSigners || actual.signerCount != 1) throw new IllegalStateException("Expected exactly one current signer");
            byte[] der = signers[0].toByteArray();
            actual.certificateBytes = der.length;
            actual.certificateDerHex = hex(der);
            actual.certificateSha256 = hex(MessageDigest.getInstance("SHA-256").digest(der));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // This is the validated PackageManager base code APK, never a requested or returned data path.
            try (FileInputStream input = new FileInputStream(actual.sourceDir)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                    actual.apkBytes = Math.addExact(actual.apkBytes, count);
                }
            }
            actual.apkSha256 = hex(digest.digest());
        }

        @Override public MetadataProbe.Stat lstat(String path) throws MetadataProbe.PlatformFailure {
            if (!path.equals("/storage") && !path.equals("/storage/self") && !path.equals("/storage/self/primary")) {
                throw new IllegalArgumentException("Not a fixed metadata path");
            }
            try {
                StructStat value = Os.lstat(path);
                return new MetadataProbe.Stat(value.st_mode, value.st_uid, value.st_gid, value.st_dev,
                        value.st_ino, value.st_size, value.st_mtime, value.st_ctime);
            } catch (ErrnoException failure) {
                throw new MetadataProbe.PlatformFailure(failure.errno, failure);
            }
        }

        @Override public String readlink(String path) throws MetadataProbe.PlatformFailure {
            if (!path.equals("/storage/self") && !path.equals("/storage/self/primary")) {
                throw new IllegalArgumentException("Not a fixed link candidate");
            }
            try { return Os.readlink(path); }
            catch (ErrnoException failure) { throw new MetadataProbe.PlatformFailure(failure.errno, failure); }
        }

        @Override public long utcMillis() { return System.currentTimeMillis(); }
        @Override public long monotonicNanos() { return SystemClock.elapsedRealtimeNanos(); }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 255;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 15];
        }
        return new String(result);
    }
}
