package com.rustedwarfare.reforge.reference.instrumentation;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

/** Production reflection seam with self-owned native-shaped shells, not an original runtime. */
public final class FixedModPreparationTest {
    private static void require(boolean ok, Object message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Throwable {
        budgetSequence();
        cleanupSequence();
        String[] modes = {"good", "save-false", "reload-throw", "parse-error", "other-enabled", "duplicate-post",
            "source-drift", "missing-definition", "wrong-name", "wrong-definition-source", "wrong-definition-mod",
            "first-count", "first-version", "duplicate-source", "wrong-source", "unallocated-enabled", "existing-error", "busy"};
        for (String mode : modes) {
            try (URLClassLoader loader = new URLClassLoader(new URL[] {Paths.get(args[0]).toUri().toURL()}, FixedModPreparationTest.class.getClassLoader())) {
                Class<?> boot = Class.forName("com.rustedwarfare.reforge.reference.boot.GbootBootstrap", true, loader);
                boot.getField("root").set(null, args[1]);
                Class<?> engineType = Class.forName("com.corrodinggames.rts.gameFramework.k", true, loader);
                engineType.getField("mode").set(null, mode); Object engine = engineType.getField("instance").get(null);
                Object settings = engineType.getField("bN").get(engine), manager = engineType.getField("bW").get(engine);
                List<?> mods = (List<?>) manager.getClass().getField("e").get(manager);
                Object selected = mods.get(0), other = mods.get(1); Class<?> modType = selected.getClass();
                if (mode.equals("first-count")) settings.getClass().getField("lastModCount").setInt(settings, -1);
                if (mode.equals("first-version")) settings.getClass().getField("modSettingsVersion").setInt(settings, 0);
                if (mode.equals("duplicate-source")) modType.getField("o").set(other, modType.getField("o").get(selected));
                if (mode.equals("wrong-source")) modType.getField("o").set(selected, "outside");
                if (mode.equals("unallocated-enabled")) modType.getField("f").setBoolean(other, false);
                if (mode.equals("existing-error")) modType.getField("P").set(selected, "first-existing");
                if (mode.equals("busy")) engineType.getField("bo").setBoolean(engine, true);
                OriginalAccess access = new OriginalAccess(loader); Map<String, Object> raw = OriginalAccess.map(); Throwable failure = null;
                String ini = Paths.get(args[1], "p001", "weaponless_tank.ini").toFile().getCanonicalPath();
                try { access.prepareFixedDefinition(ini, raw); } catch (Throwable error) { failure = error; }
                List<?> events = (List<?>) engineType.getField("events").get(null);
                if (mode.equals("good")) {
                    require(failure == null, failure); require(events.toString().equals("[B, d, save, f, reload:false:false]"), events);
                    require(Boolean.TRUE.equals(raw.get("definitionRegistered")) && Boolean.TRUE.equals(raw.get("definitionUsesSelectedMod")), raw);
                    require(!modType.getField("f").getBoolean(selected) && modType.getField("f").getBoolean(other), raw);
                    require(Boolean.TRUE.equals(raw.get("modObjectsPreserved")), raw);
                    spawnRelations(access, loader, ini, other);
                    try { access.prepareFixedDefinition(ini, OriginalAccess.map()); throw new AssertionError("repeated preparation"); }
                    catch (IllegalArgumentException expected) { require(events.size() == 5, events); }
                } else {
                    require(failure != null, mode + " accepted");
                    if (mode.equals("reload-throw")) {
                        Throwable cursor = failure;
                        while (cursor instanceof InvocationTargetException) cursor = ((InvocationTargetException) cursor).getTargetException();
                        require(cursor == engineType.getField("failure").get(null), "first exception replaced");
                    }
                    if (mode.equals("save-false")) require(events.toString().equals("[B, d, save]") && Boolean.FALSE.equals(raw.get("saveReturned")), raw);
                    if (mode.equals("parse-error")) require(raw.toString().contains("native-parser-first"), "parser error lost");
                    if (mode.equals("existing-error")) require(raw.toString().contains("first-existing") && events.size() == 1, raw);
                    if (mode.startsWith("first-") || mode.equals("duplicate-source") || mode.equals("wrong-source") || mode.equals("unallocated-enabled") || mode.equals("busy"))
                        require(events.toString().equals("[B]"), events);
                }
                require(engineType.getField("bo").getBoolean(engine) == mode.equals("busy"), "bo not restored: " + mode);
                System.out.println("PASS self-owned reflection seam " + mode);
            }
        }
    }

    private static void cleanupSequence() throws Throwable {
        final Throwable close = new IllegalStateException("first-close"), finish = new IllegalArgumentException("second-finish");
        final StringBuilder steps = new StringBuilder();
        final java.util.List<Throwable> failures = java.util.Collections.synchronizedList(new java.util.ArrayList<Throwable>());
        TrialLifecycle.cleanupActions(failures,
            new TrialLifecycle.Action() { public void run() throws Throwable { steps.append("close;"); throw close; } },
            new TrialLifecycle.Action() { public void run() {
                require(failures.size() == 1 && failures.get(0) == close, "first error not immediately visible before later action");
                steps.append("status;");
            } },
            new TrialLifecycle.Action() { public void run() throws Throwable { steps.append("finish1;"); throw finish; } },
            new TrialLifecycle.Action() { public void run() { steps.append("finish2;"); } });
        require(steps.toString().equals("close;status;finish1;finish2;"), "cleanup stopped at first error");
        require(failures.size() == 2 && failures.get(0) == close && failures.get(1) == finish, "cleanup errors lost/reordered");
        TrialLifecycle trial = new TrialLifecycle();
        final Throwable operation = new IllegalStateException("original-operation-first");
        trial.execute(new TrialLifecycle.Action() { public void run() throws Throwable { throw operation; } },
            new TrialLifecycle.Cleanup() { public java.util.Map<String, Object> run() throws Throwable { throw close; } });
        require(trial.primary == operation && trial.cleanupErrors.size() == 1 && trial.cleanupErrors.get(0) == close, "cleanup replaced operation first");
        System.out.println("PASS compound close/finish cleanup retains all errors and first operation");
    }

    private static void budgetSequence() throws Throwable {
        // Both actual gates use the same controlled monotonic clock and the production limits.
        long[][] times = {{0, 25000000000L}, {0, 25000000001L}, {25000000001L, 25000000001L},
            {60000000000L, 80000000000L}, {60000000000L, 80000000001L}, {80000000001L, 80000000001L}};
        for (int i = 0; i < times.length; i++) {
            final long[] now = {times[i][0]}; final long after = times[i][1];
            final long load = i < 3 ? 0 : 60000000000L;
            final StringBuilder events = new StringBuilder(); Throwable failure = null;
            try { TrialLifecycle.prepareThenLoad(
                new TrialLifecycle.Action() { public void run() { events.append("check;"); TrialLifecycle.requireLoadBudget(now[0], load, 0); } },
                new TrialLifecycle.Action() { public void run() { events.append("prepare;"); now[0] = after; } },
                new TrialLifecycle.Action() { public void run() { events.append("load;"); } });
            } catch (Throwable error) { failure = error; }
            boolean success = i == 0 || i == 3;
            require(success == (failure == null), events);
            require(events.toString().contains("load;") == success, "expired gate invoked map loader: " + events);
            if (i == 2 || i == 5) require(!events.toString().contains("prepare;"), events);
        }
        System.out.println("PASS controlled budget sequence 6 cases, expired preparation cannot load map");
    }

    @SuppressWarnings("unchecked")
    private static void spawnRelations(OriginalAccess access, ClassLoader loader, String ini, Object other) throws Throwable {
        Class<?> definition = Class.forName("com.corrodinggames.rts.game.units.custom.l", true, loader);
        List<Object> definitions = (List<Object>) definition.getField("d").get(null);
        Object prepared = definitions.get(0), selected = definition.getField("J").get(prepared);
        for (int mode = 0; mode < 3; mode++) {
            Object candidate = prepared;
            if (mode == 1) {
                candidate = definition.newInstance();
                for (String name : new String[] {"M", "D", "J"}) definition.getField(name).set(candidate, definition.getField(name).get(prepared));
            }
            definitions.set(0, candidate);
            if (mode == 2) definition.getField("J").set(prepared, other);
            definition.getField("factoryCalls").setInt(null, 0);
            OriginalAccess.Spawn raw = new OriginalAccess.Spawn(); Throwable failure = null;
            try { access.spawn(null, ini, raw); } catch (Throwable error) { failure = error; }
            if (mode == 0) {
                require(failure instanceof InvocationTargetException && ((InvocationTargetException) failure).getTargetException()
                    == definition.getField("factorySentinel").get(null), "positive relation did not reach factory");
                require(Boolean.TRUE.equals(raw.raw.get("preparedDefinitionSame")) && definition.getField("factoryCalls").getInt(null) == 1, raw.raw);
            } else {
                require(failure instanceof IllegalArgumentException && failure.getMessage().equals("unit-definition-not-prepared"), failure);
                require(Boolean.FALSE.equals(raw.raw.get("preparedDefinitionSame")) && definition.getField("factoryCalls").getInt(null) == 0, raw.raw);
            }
            definitions.set(0, prepared); definition.getField("J").set(prepared, selected);
        }
        System.out.println("PASS self-owned definition/J relation 3 cases, factory entry sentinel only");
    }
}
