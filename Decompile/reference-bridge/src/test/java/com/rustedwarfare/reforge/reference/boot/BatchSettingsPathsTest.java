package com.rustedwarfare.reforge.reference.boot;

import com.rustedwarfare.reforge.reference.io.FatalLatch;
import com.rustedwarfare.reforge.reference.io.PrivatePathPolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Real host files at the unchanged path check; no SharedPreferences emulation. */
public final class BatchSettingsPathsTest {
    public static void main(String[] args) throws Exception {
        if (!"/".equals(java.io.File.separator)) {
            System.err.println("UNSUPPORTED: production Android path policy requires POSIX paths; no disk-success claim");
            System.exit(77);
        }
        Path data = java.nio.file.Paths.get(args[1]).toRealPath();
        Path directory = Files.createDirectory(data.resolve("shared_prefs"));
        byte[] old = new byte[] {0, 1, 2, 3};
        Files.write(directory.resolve("rts_settings.xml"), old);
        Files.write(directory.resolve("rts_settings.xml.bak"), old);
        BatchSettings.configure("11111111-2222-4333-8444-555555555555");
        Method check = GbootBootstrap.class.getDeclaredMethod("checkPlatformSettingsPaths", Path.class, Path.class, boolean.class);
        check.setAccessible(true);
        check.invoke(null, data, directory, true);
        if (!"missing".equals(args[0])) {
            Path fresh = directory.resolve("backup".equals(args[0]) ? BatchSettings.backupName() : BatchSettings.xmlName());
            if ("directory".equals(args[0])) Files.createDirectory(fresh);
            else Files.write(fresh, new byte[] {7});
            try { check.invoke(null, data, directory, true); throw new AssertionError("preexisting new leaf accepted"); }
            catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if ("directory".equals(args[0])) {
                    if (!(cause instanceof PrivatePathPolicy.PathViolation)) throw new AssertionError("wrong type gate", cause);
                } else if (!(cause instanceof FatalLatch.Failure)
                        || !"settings-preexisting".equals(((FatalLatch.Failure)cause).code()))
                    throw new AssertionError("first missing gate changed", cause);
            }
        }
        if (!Arrays.equals(old, Files.readAllBytes(directory.resolve("rts_settings.xml")))
                || !Arrays.equals(old, Files.readAllBytes(directory.resolve("rts_settings.xml.bak"))))
            throw new AssertionError("old settings altered");
        System.out.println("PASS BatchSettingsPaths " + args[0]);
    }
}
