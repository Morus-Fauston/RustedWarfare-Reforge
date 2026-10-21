package com.rustedwarfare.reforge.reference.instrumentation;

import android.content.Context;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tests the production launch seam with isolated self-owned helper fixtures. */
public final class BootstrapLaunchOrderTest {
    private static int checks;

    public static void main(String[] args) throws Throwable {
        try (URLClassLoader loader = loader(Paths.get(args[0], "good").toString(), BootstrapLaunchOrderTest.class.getClassLoader())) {
            Class<?> helper = Class.forName("com.rustedwarfare.reforge.reference.boot.GbootBootstrap", false, loader);
            List<String> events = new ArrayList<String>();
            helper.getField("events").set(null, events);
            OriginalAccess original = new OriginalAccess(loader);
            Context target = context(loader);
            TrialLifecycle trial = new TrialLifecycle();
            OriginalInitialInstrumentation.launchAfterIdentity(trial, original, target,
                    () -> events.add("identity"), () -> {
                        events.add("launcher");
                        check(helper.getField("ready").getBoolean(null), "Private roots are not initialized");
                    });
            check(events.equals(Arrays.asList("engine-null", "identity", "init", "roots", "launcher")),
                    "production order was " + events);
            check(helper.getField("context").get(null) == target, "init receives exact target Context object");
            check("launcher-ready".equals(trial.stage), "launcher stage reached after roots");
            OriginalInitialInstrumentation.launchAfterIdentity(trial, original, target,
                    () -> events.add("identity"), () -> check(helper.getField("ready").getBoolean(null), "repeated same Context ready"));
            check(helper.getField("context").get(null) == target, "repeated same Context preserved");
        }
        for (String stage : Arrays.asList("init", "roots")) failure(args[0], stage);
        identityFailure(args[0]);
        for (String variant : Arrays.asList("nonstatic", "wrong-result", "wrong-parameter", "nonpublic")) descriptor(args[0], variant);
        wrongLoader(args[0]);
        System.out.println("PASS BootstrapLaunchOrder scenarios=10 checks=" + checks + " production-seam-and-real-reflection");
    }

    private static URLClassLoader loader(String path, ClassLoader parent) throws Exception {
        return new URLClassLoader(new URL[] {Paths.get(path).toUri().toURL()}, parent);
    }

    private static Context context(ClassLoader loader) throws Exception {
        // The retained host runner installs this constructor in a self-owned type shell.
        // The test source still compiles against the unmodified Android API jar.
        return (Context) Context.class.getConstructor(ClassLoader.class).newInstance(loader);
    }

    private static void failure(String root, String stage) throws Throwable {
        try (URLClassLoader loader = loader(Paths.get(root, "good").toString(), BootstrapLaunchOrderTest.class.getClassLoader())) {
            Class<?> helper = Class.forName("com.rustedwarfare.reforge.reference.boot.GbootBootstrap", false, loader);
            List<String> events = new ArrayList<String>(); helper.getField("events").set(null, events);
            RuntimeException cause = new IllegalStateException(stage + "-first-failure");
            helper.getField(stage + "Failure").set(null, cause);
            OriginalAccess original = new OriginalAccess(loader);
            TrialLifecycle trial = new TrialLifecycle(); trial.stage = "identity";
            boolean[] launcherRequested = {false};
            trial.execute(() -> OriginalInitialInstrumentation.launchAfterIdentity(trial, original, context(loader),
                    () -> events.add("identity"), () -> { launcherRequested[0] = true; events.add("launcher"); }),
                    () -> {
                        trial.cleanupOutcome.put("launcherRequested", launcherRequested[0]);
                        trial.cleanupOutcome.put("attempted", false);
                        trial.cleanupOutcome.put("skipReason", "no-observed-owned-target-activity");
                        return trial.cleanupOutcome;
                    });
            check(!launcherRequested[0] && !events.contains("launcher"), stage + " failure is zero launch");
            check("bootstrap-before-launch".equals(trial.primaryStage), stage + " primary stage");
            check(trial.primary instanceof java.lang.reflect.InvocationTargetException &&
                    ((java.lang.reflect.InvocationTargetException) trial.primary).getTargetException() == cause, stage + " exact reflected cause");
            check(Boolean.FALSE.equals(trial.cleanupOutcome.get("launcherRequested")) &&
                    "no-observed-owned-target-activity".equals(trial.cleanupOutcome.get("skipReason")), stage + " no owned Activity cleanup");
            Throwable primary = trial.primary;
            RuntimeException transport = new IllegalArgumentException(stage + "-failure-send");
            trial.attemptFailureTransport(() -> {
                check(trial.primary == primary && "bootstrap-before-launch".equals(trial.primaryStage), "failure transport sees unchanged primary");
                throw transport;
            });
            check(trial.primary == primary && trial.transportErrors.size() == 1 && trial.transportErrors.get(0) == transport,
                    "transport failure keeps primary and separate error for final");
            check(((java.lang.reflect.InvocationTargetException) trial.primary).getTargetException() == cause, "final lifecycle retains exact cause");
            check(events.equals("init".equals(stage) ? Arrays.asList("engine-null", "identity", "init") :
                    Arrays.asList("engine-null", "identity", "init", "roots")), "failure stops at " + stage);
        }
    }

    private static void identityFailure(String root) throws Throwable {
        try (URLClassLoader loader = loader(Paths.get(root, "good").toString(), BootstrapLaunchOrderTest.class.getClassLoader())) {
            Class<?> helper = Class.forName("com.rustedwarfare.reforge.reference.boot.GbootBootstrap", false, loader);
            List<String> events = new ArrayList<String>(); helper.getField("events").set(null, events);
            OriginalAccess original = new OriginalAccess(loader);
            TrialLifecycle trial = new TrialLifecycle(); trial.stage = "identity";
            RuntimeException first = new IllegalStateException("identity-send-failed");
            trial.execute(() -> OriginalInitialInstrumentation.launchAfterIdentity(trial, original, context(loader),
                    () -> trial.transport(() -> { events.add("identity"); throw first; }), () -> events.add("launcher")),
                    () -> ReadbackEvidence.map());
            check(trial.primary == first && "identity".equals(trial.primaryStage), "identity emit failure stays before init");
            check(events.equals(Arrays.asList("engine-null", "identity")), "no init or launch before successful identity emit");
        }
    }

    private static void descriptor(String root, String variant) throws Throwable {
        try (URLClassLoader loader = loader(Paths.get(root, variant).toString(), BootstrapLaunchOrderTest.class.getClassLoader())) {
            Class<?> helper = Class.forName("com.rustedwarfare.reforge.reference.boot.GbootBootstrap", false, loader);
            List<String> events = new ArrayList<String>(); helper.getField("events").set(null, events);
            TrialLifecycle trial = new TrialLifecycle(); trial.stage = "identity";
            OriginalAccess original = new OriginalAccess(loader);
            trial.execute(() -> OriginalInitialInstrumentation.launchAfterIdentity(trial, original, context(loader),
                    () -> events.add("identity"), () -> events.add("launcher")), () -> ReadbackEvidence.map());
            check(trial.primary instanceof IllegalArgumentException || trial.primary instanceof NoSuchMethodException,
                    variant + " exact descriptor rejected");
            check("bootstrap-before-launch".equals(trial.primaryStage) && events.equals(Arrays.asList("engine-null", "identity")),
                    variant + " refusal before init body and launcher");
        }
    }

    private static void wrongLoader(String root) throws Throwable {
        try (URLClassLoader parent = loader(Paths.get(root, "parent-helper").toString(), BootstrapLaunchOrderTest.class.getClassLoader());
                URLClassLoader loader = loader(Paths.get(root, "child-owners").toString(), parent)) {
            Class<?> helper = Class.forName("com.rustedwarfare.reforge.reference.boot.GbootBootstrap", false, parent);
            List<String> events = new ArrayList<String>(); helper.getField("events").set(null, events);
            TrialLifecycle trial = new TrialLifecycle(); trial.stage = "identity";
            OriginalAccess original = new OriginalAccess(loader);
            trial.execute(() -> OriginalInitialInstrumentation.launchAfterIdentity(trial, original, context(loader),
                    () -> events.add("identity"), () -> events.add("launcher")), () -> ReadbackEvidence.map());
            check(trial.primary instanceof IllegalArgumentException && trial.primary.getMessage().startsWith("original-public-owner:"),
                    "parent-loaded bootstrap refused");
            check(events.equals(Arrays.asList("engine-null", "identity")), "wrong helper loader is zero init and launcher");
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
