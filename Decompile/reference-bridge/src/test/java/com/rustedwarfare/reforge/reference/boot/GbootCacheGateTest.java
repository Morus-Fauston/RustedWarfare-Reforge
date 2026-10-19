package com.rustedwarfare.reforge.reference.boot;

import com.rustedwarfare.reforge.reference.io.FatalLatch;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Public API gate only; reflection lets the initial missing API fail at runtime. */
public final class GbootCacheGateTest {
    private interface Action { void run(); }

    private static void path() {
        final Method method;
        try {
            method = GbootBootstrap.class.getMethod("privateCachePath", String.class, String.class, boolean.class);
        } catch (NoSuchMethodException error) {
            throw new AssertionError("Missing public cache API: privateCachePath", error);
        }
        try {
            method.invoke(null, "mods-info", "owned.data", false);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError("Unexpected checked public API failure", cause);
        } catch (IllegalAccessException error) {
            throw new AssertionError("Cache API must be public static", error);
        }
    }

    private static void lifecycle(String name) {
        final Method method;
        final Object[] arguments;
        try {
            if ("countPrivateCaches".equals(name) || "clearPrivateCaches".equals(name)) {
                method = GbootBootstrap.class.getMethod(name);
                arguments = new Object[0];
            } else if ("writePrivateCache".equals(name)) {
                method = GbootBootstrap.class.getMethod(name, String.class, String.class, java.io.InputStream.class);
                arguments = new Object[] {"mods-info", "owned.data", null};
            } else {
                method = GbootBootstrap.class.getMethod(name, String.class, String.class);
                arguments = new Object[] {"mods-info", "owned.data"};
            }
        } catch (NoSuchMethodException error) {
            throw new AssertionError("Missing public cache API: " + name, error);
        }
        try {
            method.invoke(null, arguments);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError("Unexpected checked public API failure", cause);
        } catch (IllegalAccessException error) {
            throw new AssertionError("Cache API must be public static", error);
        }
    }

    private static FatalLatch.Failure failure(String code, Action action) {
        try {
            action.run();
            throw new AssertionError("Expected fatal " + code);
        } catch (FatalLatch.Failure error) {
            if (!code.equals(error.code()))
                throw new AssertionError("Expected " + code + ", got " + error.code());
            return error;
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("not-ready or prefailed required");
        FatalLatch.Failure first;
        if ("all-not-ready".equals(args[0])) {
            first = failure("roots-not-ready", () -> lifecycle("openPrivateCache"));
            for (String name : new String[] {"openPrivateCache", "writePrivateCache", "deletePrivateCache",
                    "countPrivateCaches", "clearPrivateCaches"}) {
                if (failure(first.code(), () -> lifecycle(name)) != first)
                    throw new AssertionError("Cache lifecycle replaced first failure: " + name);
            }
            System.out.println("PASS GbootCacheGate all-not-ready checks=6 (no initialized Context)");
            return;
        } else if ("not-ready".equals(args[0])) {
            first = failure("roots-not-ready", GbootCacheGateTest::path);
        } else if ("prefailed".equals(args[0])) {
            first = failure("state-decode-failed", () -> GbootBootstrap.requireStateDecoded(false));
            if (failure(first.code(), GbootCacheGateTest::path) != first)
                throw new AssertionError("Cache path replaced the prefailure object");
        } else throw new IllegalArgumentException("Unknown scenario " + args[0]);
        if (failure(first.code(), GbootCacheGateTest::path) != first)
            throw new AssertionError("Cache path replaced the first failure object");
        if (failure(first.code(), () -> GbootBootstrap.requireStateDecoded(false)) != first)
            throw new AssertionError("Cache path does not share the bootstrap fatal latch");
        System.out.println("PASS GbootCacheGate " + args[0]
                + " checks=3 (public early gate only; no Context, filesystem success or Android claim)");
    }
}
