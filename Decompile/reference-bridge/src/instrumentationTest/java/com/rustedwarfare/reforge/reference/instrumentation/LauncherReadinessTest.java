package com.rustedwarfare.reforge.reference.instrumentation;

public final class LauncherReadinessTest {
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    private static void rejected(Runnable action) {
        try { action.run(); throw new AssertionError("accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        final LauncherReadiness launch = new LauncherReadiness();
        require(!launch.intro(1) && !launch.menu(1) && !launch.ready());
        launch.begin(10, 100);
        require(!launch.menu(11) && !launch.intro(9));
        require(launch.intro(12) && !launch.intro(13));
        require(!launch.menu(11) && launch.menu(14) && !launch.menu(15));
        require(launch.ready()); launch.checkDeadline(99);
        // Even a previously observed Menu cannot bypass a late dispatch return.
        rejected(new Runnable() { public void run() { launch.checkDeadline(100); } });
        rejected(new Runnable() { public void run() { launch.begin(20, 110); } });
        require(launch.dispatchNanos() == 10 && launch.introNanos() == 12 && launch.menuNanos() == 14);
        launch.close(); require(!launch.ready() && launch.closedAfterRequest());
        require(!launch.intro(30) && !launch.menu(30));
        rejected(new Runnable() { public void run() { launch.checkDeadline(30); } });
        final LauncherReadiness absent = new LauncherReadiness(); absent.begin(0, 5);
        require(!absent.intro(5) && !absent.menu(5) && !absent.ready());
        absent.close(); require(absent.closedAfterRequest());
        final LauncherReadiness closed = new LauncherReadiness(); closed.close();
        require(!closed.closedAfterRequest());
        rejected(new Runnable() { public void run() { closed.begin(0, 5); } });
        final RuntimeException first = new IllegalStateException("launch-first");
        final RuntimeException cleanup = new IllegalStateException("cleanup-second");
        TrialLifecycle trial = new TrialLifecycle(); trial.stage = "launcher-ready";
        trial.execute(new TrialLifecycle.Action() { public void run() { throw first; } },
                new TrialLifecycle.Cleanup() { public java.util.Map<String, Object> run() { throw cleanup; } });
        require(trial.primary == first && "launcher-ready".equals(trial.primaryStage)
                && trial.cleanupErrors.size() == 1 && trial.cleanupErrors.get(0) == cleanup);
        System.out.println("PASS launcher window: order, deadline, late callbacks, one dispatch");
    }
}
