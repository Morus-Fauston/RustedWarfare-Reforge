package com.rustedwarfare.reforge.reference.instrumentation;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** One fresh target process; fixed initial trial and optional four normal-thread motion segments. */
public final class OriginalSceneInstrumentation extends Instrumentation {
    private static final String TARGET = "com.corrodinggames.rts";
    private static final String TEST = "com.rustedwarfare.reforge.reference.test";
    private static final String INTRO = TARGET + ".appFramework.IntroScreen";
    private static final String MENU = TARGET + ".appFramework.MainMenuActivity";
    private static final String INGAME = TARGET + ".appFramework.InGameActivity";
    private static final String MAIN_DEX = "436572cc77435ade9838f72335a905c7a4b4a591049f9e2e0d0a9bfec0395e1f";
    private static final String HELPER_DEX = "cfc3ad37e81cd03b8a976a5b15eb003c0fa9792b4acd6b746c521dd58328f4ff";
    private static final String[] SEEDS = {"maps/p001-controlled.tmx", "units/p001/weaponless_tank.ini", "units/p001/tank.png"};
    private static final long[] SEED_LENGTHS = {6830, 1138, 1158};
    private static final String[] SEED_HASHES = {
        "3a01ec370e490497c13093012fa04615d47f84001aa80684335e155385ecc04b",
        "f4d2bd6dee6174a558d4aada487a4565c8e3636229c5ef33ac89607f4733e9eb",
        "ba7a15e3d4c8f406ad4f8257716b2355003c0324e935d5633260e2a29944dca3"};
    private final InitialProtocol.Stream stream = new InitialProtocol.Stream();
    private final Map<String, String> request = new LinkedHashMap<String, String>();
    private final Map<String, Object> observed = new LinkedHashMap<String, Object>();
    private final List<Activity> activities = Collections.synchronizedList(new ArrayList<Activity>());
    private volatile Activity resumedMenu;
    private volatile Activity observedIntro;
    private volatile long menuResumeNanos;
    private final LauncherReadiness launchWindow = new LauncherReadiness();
    private final List<Throwable> lateLaunchErrors = Collections.synchronizedList(new ArrayList<Throwable>());
    private Bundle arguments;
    private final TrialLifecycle trial = new TrialLifecycle();
    private SceneAccess original;
    private boolean launcherRequested;
    private String lastCompleted = "none";
    private long startNanos;
    private boolean seedStable;
    private boolean initialCaptured;
    private SceneDriver motionDriver;
    private Map<String,Object> motionSummary;
    private boolean motionCaptured;
    private final List<Throwable> motionErrors=new ArrayList<Throwable>();
    private volatile boolean motionUiRequested;
    private volatile Activity resumedGame;
    private volatile Throwable motionUiError;
    private final List<Throwable> motionUiErrors=Collections.synchronizedList(new ArrayList<Throwable>());
    private final CountDownLatch motionUiResumed=new CountDownLatch(1);
    private final MotionUiWindow motionUiWindow=new MotionUiWindow();
    private volatile long gameResumeNanos;
    private void motionUiFailed(Throwable error){synchronized(motionUiErrors){motionUiErrors.add(error);if(motionUiError==null)motionUiError=error;}}

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : new Bundle(arguments);
        start();
    }

    private void track(Activity activity) {
        String name = activity.getClass().getName();
        if (TARGET.equals(activity.getPackageName()) && (INTRO.equals(name) || MENU.equals(name) || motionUiRequested && INGAME.equals(name)))
            synchronized (activities) { if (!activities.contains(activity)) activities.add(activity); }
    }

    @Override public void callActivityOnCreate(Activity activity, Bundle state) {
        track(activity);
        if(motionUiRequested && TARGET.equals(activity.getPackageName()) && INGAME.equals(activity.getClass().getName())) {
            try{motionUiWindow.created();}catch(Throwable error){motionUiFailed(error);}
        }
        super.callActivityOnCreate(activity, state);
        if (TARGET.equals(activity.getPackageName()) && INTRO.equals(activity.getClass().getName())
                && launchWindow.intro(SystemClock.elapsedRealtimeNanos())) observedIntro = activity;
        finishLateLaunch(activity);
    }

    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        String name = activity.getClass().getName();
        if (TARGET.equals(activity.getPackageName()) && (INTRO.equals(name) || MENU.equals(name))) {
            synchronized (activities) { if (!activities.contains(activity)) activities.add(activity); }
            long now = SystemClock.elapsedRealtimeNanos();
            if (MENU.equals(name) && launchWindow.menu(now)) { menuResumeNanos = now; resumedMenu = activity; }
        }
        if(motionUiRequested && TARGET.equals(activity.getPackageName()) && INGAME.equals(name)) {
            try {
                boolean ready=motionUiWindow.resume(SystemClock.elapsedRealtimeNanos(),new MotionUiWindow.Action(){
                    @Override public void run()throws Throwable{original.stopForMotionUi();}
                });
                if(ready){resumedGame=activity;gameResumeNanos=SystemClock.elapsedRealtimeNanos();}
                else activity.finish();
            } catch(Throwable error) { motionUiFailed(error); }
            finally { motionUiResumed.countDown(); }
        }
        finishLateLaunch(activity);
    }

    private void finishLateLaunch(Activity activity) {
        String name = activity.getClass().getName();
        if (launchWindow.closedAfterRequest() && TARGET.equals(activity.getPackageName())
                && (INTRO.equals(name) || MENU.equals(name) || motionUiRequested && INGAME.equals(name)) && !activity.isFinishing() && !activity.isDestroyed()) {
            try { activity.finish(); } catch (Throwable error) { lateLaunchErrors.add(error); }
        }
    }

    @Override public void onStart() {
        super.onStart(); startNanos = SystemClock.elapsedRealtimeNanos();
        trial.execute(new TrialLifecycle.Action() { @Override public void run() throws Throwable { runTrial(); } },
                new TrialLifecycle.Cleanup() { @Override public Map<String, Object> run() throws Throwable { return cleanupActivities(trial.cleanupOutcome); } });
        if (trial.primary == null) {
            try { emit("cleanup", trial.cleanupOutcome); }
            catch (Throwable error) { trial.primary = error; trial.primaryStage = "cleanup-transport"; }
        }
        if (trial.primary != null) trial.attemptFailureTransport(new TrialLifecycle.Action() {
            @Override public void run() throws Throwable {
                Map<String, Object> failure = error(trial.primary, trial.primaryStage);
                failure.put("partialObservations", trial.pending); failure.put("cleanupOutcome", trial.cleanupOutcome);
                failure.put("cleanupErrors", errors(trial.cleanupErrors, "cleanup"));
                failure.put("readbackErrors", errors(trial.readbackErrors, "best-effort-readback"));
                sendStatus(1, record("failure", failure)); trial.emitted("failure");
            }
        });
        boolean motionRequested=ScenePlan.motionMode(request)!=null;
        boolean success = trial.primary == null && trial.cleanupErrors.isEmpty() && trial.transportErrors.isEmpty() && initialCaptured && seedStable
            && (!motionRequested || motionCaptured) && motionUiErrors.isEmpty();
        Map<String, Object> result = map(); result.put("status", success ? (motionRequested ? "CAPTURED_MOTION" : "CAPTURED_INITIAL") : "FAILED");
        synchronized(motionUiErrors){result.put("motionUiErrors",errors(motionUiErrors,"motion-ui-lifecycle"));}
        if(motionRequested){result.put("motion",motionSummary);result.put("motionCaptured",motionCaptured);result.put("motionErrors",errors(motionErrors,"normal-motion"));}
        result.put("lastCompletedStage", lastCompleted); result.put("primaryError", trial.primary == null ? null : error(trial.primary, trial.primaryStage));
        result.put("cleanupOutcome", trial.cleanupOutcome); result.put("cleanupErrors", errors(trial.cleanupErrors, "cleanup"));
        result.put("transportErrors", errors(trial.transportErrors, "transport")); result.put("readbackErrors", errors(trial.readbackErrors, "best-effort-readback"));
        result.put("partialObservations", trial.pending); result.put("recordHashes", stream.hashes()); result.put("recordCount", stream.hashes().size());
        result.put("seedStable", seedStable); result.put("initialSnapshotCaptured", initialCaptured);
        result.put("originalRuntimeVerified", false); result.put("comparisonAccepted", false); result.put("finalAcceptancePassed", false);
        try { finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, record("final", result)); }
        catch (Throwable finalError) { finish(Activity.RESULT_CANCELED, new Bundle()); }
    }

    private void runTrial() throws Throwable {
        for (String key : arguments.keySet()) {
            Object value = arguments.get(key); InitialProtocol.require(value instanceof String, "argument-type:" + key); request.put(key, (String) value);
        }
        ScenePlan.validate(request); deadline(90000);
        trial.stage = "identity"; trial.observe("identity", observed); checkIdentity();
        original = new SceneAccess(getTargetContext().getClassLoader(),ScenePlan.from(request));
        launchAfterIdentity(trial, original, getTargetContext(),
                new TrialLifecycle.Action() { @Override public void run() throws Throwable {
                    emit("identity", observed);
                    original.configureSettings(request.get("runId"));
                } },
                new TrialLifecycle.Action() { @Override public void run() throws Throwable { launchOriginal(); } });
        trial.stage = "bootstrap";
        final Map<String, Object> bootstrap = map(); trial.observe("bootstrap", bootstrap);
        bootstrap.put("settingsBatch", original.settingsBatch(request.get("runId")));
        original.boot("requireRootsReady", void.class); bootstrap.put("requireRootsReadyReturned", true);
        original.boot("requireBackendPublished", void.class); bootstrap.put("requireBackendPublishedReturned", true);
        List<Object> roots = new ArrayList<Object>(); bootstrap.put("roots", roots);
        final Path files = ReadbackFiles.anchor(getTargetContext().getFilesDir().toPath(), roots);
        final Path root = files.resolve("reference-test"); ReadbackFiles.fixedParents(files, root, roots);
        InitialProtocol.require(root.toString().equals(original.boot("privateRoot", String.class)), "private-root-mismatch");
        for (String name : new String[] {"maps", "units", "saves", "replays", "cache", "bridge"}) ReadbackFiles.fixedParents(root, root.resolve(name), roots);
        for (String name : new String[] {"mods-info", "mods-dir-search", "mods-dir-music"}) ReadbackFiles.fixedParents(root, root.resolve("cache").resolve(name + ".cachedata"), roots);
        InitialProtocol.require(root.resolve("maps").toString().equals(original.boot("mapsRoot", String.class))
                && root.resolve("units").toString().equals(original.boot("unitsRoot", String.class)), "private-leaf-root-mismatch");
        List<Object> seedRows = new ArrayList<Object>(); bootstrap.put("seeds", seedRows); seeds(files, root, seedRows);
        bootstrap.put("bootstrapReady", true); emit("bootstrap", bootstrap);
        final ScenePlan plan=ScenePlan.from(request);
        final String mapPath = SceneInputs.prepare(getContext(),files,root,request.get("runId"),plan); final SceneAccess.Snapshot[] before = new SceneAccess.Snapshot[1];
        final Map<String, Object> modPreparation = map(); trial.observe("native-mod-preparation", modPreparation);
        trial.stage = "native-load"; final long loadStarted = SystemClock.elapsedRealtimeNanos();
        trial.runObserved(new TrialLifecycle.Action() { @Override public void run() throws Throwable {
                    TrialLifecycle.prepareThenLoad(
                        new TrialLifecycle.Action() { @Override public void run() {
                            TrialLifecycle.requireLoadBudget(SystemClock.elapsedRealtimeNanos(), loadStarted, startNanos);
                        } },
                        new TrialLifecycle.Action() { @Override public void run() throws Throwable {
                            original.prepareFixedDefinition(root.resolve(SEEDS[1]).toString(), modPreparation);
                        } },
                        new TrialLifecycle.Action() { @Override public void run() throws Throwable {
                            original.load(mapPath);
                            if(ScenePlan.motionMode(request)!=null) prepareMotionUi(mapPath,modPreparation);
                        } });
                } },
                new TrialLifecycle.Action() { @Override public void run() throws Throwable {
                    trial.stage = "native-pre-spawn"; before[0] = original.snapshot(mapPath, null);
                    before[0].raw.put("modPreparation", modPreparation);
                    before[0].raw.put("operationSucceeded", !trial.operationFailed);
                    if (before[0].raw.containsKey("bGSize")) before[0].raw.put("preSize", before[0].raw.get("bGSize"));
                    publishObservation("native-pre-spawn", before[0].raw, new TrialLifecycle.Action() { @Override public void run() throws Throwable {
                        before[0].requireReadable();
                        if (!trial.operationFailed) {
                            before[0].requirePre(original);
                            InitialProtocol.require(SystemClock.elapsedRealtimeNanos() - loadStarted <= TimeUnit.SECONDS.toNanos(25), "native-load-deadline"); deadline(80000);
                        }
                    }});
                    trial.emitted("native-mod-preparation");
                }});
        trial.stage = "factory"; final long factoryStarted = SystemClock.elapsedRealtimeNanos();
        final SceneAccess.Spawn created = new SceneAccess.Spawn(); trial.observe("factory", created.raw);
        trial.runObserved(new TrialLifecycle.Action() { @Override public void run() throws Throwable { original.spawn(before[0].player, root.resolve(SEEDS[1]).toString(), created); } },
                new TrialLifecycle.Action() { @Override public void run() throws Throwable {
                    trial.stage = "native-initial"; final SceneAccess.Snapshot after = original.snapshot(mapPath, created.definition);
                    after.raw.put("operationSucceeded", !trial.operationFailed); after.raw.put("factory", created.raw);
                    after.raw.put("factoryReturnedObjectIsSolePostEntry", created.unit == null || after.units == null ? null : after.units.length == 1 && after.units[0] == created.unit);
                    after.raw.put("preSize", before[0].raw.get("bGSize"));
                    if (after.raw.containsKey("bGSize")) after.raw.put("postSize", after.raw.get("bGSize"));
                    after.raw.put("initializationOrigin", "instrumentation-public-factory-after-real-map-load");
                    after.raw.put("factoryRawArguments", java.util.Arrays.asList(false, plan.x, plan.y, plan.ci));
                    publishObservation("native-initial", after.raw, new TrialLifecycle.Action() { @Override public void run() throws Throwable {
                        after.requireReadable();
                        if (!trial.operationFailed) {
                            original.requireInitial(before[0], after, created);
                            List<Object> rows = new ArrayList<Object>(); after.raw.put("seedsAfter", rows); seeds(files, root, rows); seedStable = true;
                            InitialProtocol.require(SystemClock.elapsedRealtimeNanos() - factoryStarted <= TimeUnit.SECONDS.toNanos(10), "native-initial-deadline"); deadline(80000);
                        }
                    }});
                    trial.emitted("factory");
                }});
        initialCaptured = true;
        final String motionMode=ScenePlan.motionMode(request);
        if(motionMode!=null){
            if(motionUiError!=null)throw motionUiError;
            seedStable=false;
            trial.stage="normal-motion";deadline(90000);
            motionDriver=new SceneDriver(getTargetContext().getClassLoader(),original,original.engine(),created.unit,before[0].player,
                created.definition,mapPath,request.get("runId"),motionMode,plan);
            final SceneWire wire=new SceneWire(new SceneWire.Output(){@Override public void send(String raw){
                Bundle value=new Bundle();value.putString("r21Record",raw);sendStatus(1,value);
            }},request.get("runId"),motionMode,request.get("targetApkSha256"),request.get("testApkSha256"),stream.hashes().get(4),plan,motionDriver.sceneInitial());
            Throwable first=null;wire.begin();
            try{
                motionDriver.run(wire);deadline(90000);
                List<Object> rows=new ArrayList<Object>();seeds(files,root,rows);wire.seeds(rows);seedStable=true;
            }catch(Throwable error){first=error;motionErrors.add(error);}
            finally{
                try{
                    Map<String,Object> full=wire.end(first==null,motionDriver.errors(),motionDriver.finalFacts());
                    motionSummary=map();motionSummary.put("success",full.get("success"));motionSummary.put("phaseCount",full.get("phaseCount"));
                    motionSummary.put("completedPhaseCount",full.get("completedPhaseCount"));motionSummary.put("recordCount",full.get("recordCount"));motionSummary.put("bytes",full.get("bytes"));
                    @SuppressWarnings("unchecked") List<String> hashes=(List<String>)full.get("recordHashes");
                    motionSummary.put("lastRecordSha256",hashes.get(hashes.size()-1));
                }catch(Throwable error){motionErrors.add(error);if(first==null)first=error;else if(first!=error)first.addSuppressed(error);}
            }
            if(first!=null)throw first;deadline(90000);motionCaptured=true;
        }
    }

    private void prepareMotionUi(String mapPath,Map<String,Object> preparation) throws Throwable {
        final Map<String,Object> ui=map();preparation.put("motionUi",ui);
        ui.put("before",original.motionUiState());
        SceneAccess.Snapshot empty=original.snapshot(mapPath,null);empty.requireReadable();empty.requirePre(original);
        ui.put("emptyBefore",empty.raw);
        final long end=Math.min(startNanos+TimeUnit.SECONDS.toNanos(80),SystemClock.elapsedRealtimeNanos()+TimeUnit.SECONDS.toNanos(10));
        motionUiWindow.begin(SystemClock.elapsedRealtimeNanos(),end);
        motionUiRequested=true;ui.put("dispatchNanos",SystemClock.elapsedRealtimeNanos());
        final CountDownLatch dispatched=new CountDownLatch(1);
        final List<Throwable> dispatchErrors=Collections.synchronizedList(new ArrayList<Throwable>());
        boolean posted=new Handler(Looper.getMainLooper()).post(new Runnable(){@Override public void run(){
            try {
                InitialProtocol.require(motionUiWindow.dispatch(SystemClock.elapsedRealtimeNanos(),new MotionUiWindow.Action(){
                    @Override public void run()throws Throwable{
                        original.closeUpdateDialogs();
                        getTargetContext().startActivity(new Intent().setClassName(TARGET,INGAME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                }),"motion-ui-dispatch-cancelled");
            }catch(Throwable error){dispatchErrors.add(error);motionUiFailed(error);}finally{dispatched.countDown();}
        }});
        ui.put("mainPosted",posted);InitialProtocol.require(posted,"motion-ui-main-post");
        InitialProtocol.require(dispatched.await(Math.max(1L,TimeUnit.NANOSECONDS.toMillis(end-SystemClock.elapsedRealtimeNanos())),TimeUnit.MILLISECONDS),"motion-ui-dispatch-timeout");
        if(!dispatchErrors.isEmpty())throw dispatchErrors.get(0);
        InitialProtocol.require(motionUiResumed.await(Math.max(1L,TimeUnit.NANOSECONDS.toMillis(end-SystemClock.elapsedRealtimeNanos())),TimeUnit.MILLISECONDS),"motion-ui-resume-timeout");
        if(motionUiError!=null)throw motionUiError;
        InitialProtocol.require(resumedGame!=null && !resumedGame.isFinishing() && !resumedGame.isDestroyed(),"motion-ui-destroyed");
        ui.put("resumeNanos",gameResumeNanos);ui.put("activity",resumedGame.getClass().getName());
        ui.put("after",MotionUiGate.awaitReady(new MotionUiGate.Poll(){
            @Override public Map<String,Object> read()throws Throwable{if(motionUiError!=null)throw motionUiError;return original.motionUiState(resumedGame);}
            @Override public long now(){return SystemClock.elapsedRealtimeNanos();}
            @Override public void pause(){SystemClock.sleep(10);}
        },end));
        @SuppressWarnings("unchecked") Map<String,Object> after=(Map<String,Object>)ui.get("after");
        MotionUiGate.requireReady(after);
        SceneAccess.Snapshot stopped=original.snapshot(mapPath,null);stopped.requireReadable();stopped.requirePre(original);
        InitialProtocol.require(stopped.engine==empty.engine && stopped.map==empty.map && stopped.player==empty.player,"motion-ui-world-replaced");
        ui.put("emptyAfter",stopped.raw);ui.put("ready",true);
    }

    static void launchAfterIdentity(TrialLifecycle trial, SceneAccess original, Context targetContext,
            TrialLifecycle.Action emitIdentity, TrialLifecycle.Action launcher) throws Throwable {
        InitialProtocol.require(original.engine() == null, "target-engine-preexisting");
        emitIdentity.run();
        trial.stage = "bootstrap-before-launch";
        original.initialize(targetContext);
        original.boot("requireRootsReady", void.class);
        trial.stage = "launcher-ready";
        launcher.run();
    }

    private void launchOriginal() throws Throwable {
        long launcherEnd = Math.min(startNanos + TimeUnit.SECONDS.toNanos(90), SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(45));
        launchWindow.begin(SystemClock.elapsedRealtimeNanos(), launcherEnd);
        launcherRequested = true;
        getTargetContext().startActivity(new Intent().setClassName(TARGET, INTRO).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        launchWindow.checkDeadline(SystemClock.elapsedRealtimeNanos());
        for (;;) {
            launchWindow.checkDeadline(SystemClock.elapsedRealtimeNanos());
            if (launchWindow.ready() && observedIntro != null && resumedMenu != null) break;
            SystemClock.sleep(50);
        }
        launchWindow.checkDeadline(SystemClock.elapsedRealtimeNanos());
        InitialProtocol.require(INTRO.equals(observedIntro.getClass().getName()) && TARGET.equals(observedIntro.getPackageName())
                && MENU.equals(resumedMenu.getClass().getName()) && TARGET.equals(resumedMenu.getPackageName()), "launcher-identity");
        InitialProtocol.require(original.engine() != null, "menu-without-engine");
        launchWindow.checkDeadline(SystemClock.elapsedRealtimeNanos());
        Map<String, Object> launcher = map(); launcher.put("activity", resumedMenu.getClass().getName()); launcher.put("observedOnResumeNanos", menuResumeNanos);
        launcher.put("introActivity", observedIntro.getClass().getName()); launcher.put("dispatchNanos", launchWindow.dispatchNanos());
        launcher.put("observedIntroNanos", launchWindow.introNanos()); launcher.put("observedMenuNanos", launchWindow.menuNanos());
        launcher.put("dispatchMode", "target-context-explicit-new-task-no-idle-wait"); emit("launcher-ready", launcher);
    }

    private void publishObservation(String kind, Map<String, Object> raw, TrialLifecycle.Action validate) throws Throwable {
        trial.observe(kind, raw); Throwable first = null; String failedAt = null;
        try { validate.run(); } catch (Throwable error) { first = error; failedAt = trial.stage; }
        try { emit(kind, raw); } catch (Throwable error) { if (first == null) { first = error; failedAt = trial.stage; } else trial.readbackErrors.add(error); }
        if (first != null) { trial.stage = failedAt; throw first; }
    }

    private void checkIdentity() throws Exception {
        InitialProtocol.require(Build.VERSION.SDK_INT >= 28, "signing-info-api-unavailable");
        observed.put("actualTargetContextPackage", getTargetContext().getPackageName()); observed.put("actualTestContextPackage", getContext().getPackageName());
        observed.put("actualProcessName", Application.getProcessName()); observed.put("actualUid", Process.myUid());
        InitialProtocol.require(TARGET.equals(getTargetContext().getPackageName()) && TEST.equals(getContext().getPackageName())
                && TARGET.equals(Application.getProcessName()), "target-package-process");
        PackageManager pm = getTargetContext().getPackageManager();
        PackageInfo target = pm.getPackageInfo(TARGET, PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo test = pm.getPackageInfo(TEST, PackageManager.GET_SIGNING_CERTIFICATES);
        observed.put("targetApplicationUid", target.applicationInfo.uid); observed.put("testApplicationUid", test.applicationInfo.uid);
        InitialProtocol.require(target.applicationInfo.uid == Process.myUid(), "target-uid");
        Map<String, Object> signerRows = map(); observed.put("signers", signerRows);
        Map<String, Object> targetSigner = map(), testSigner = map(); signerRows.put("target", targetSigner); signerRows.put("test", testSigner);
        String signer = signer(target, targetSigner); observed.put("observedSignerSha256", signer);
        String testSignerHash = signer(test, testSigner);
        InitialProtocol.require(signer.equals(testSignerHash) && InitialProtocol.SIGNER_HASH.equals(signer), "installed-signer");
        Map<String, Object> apks = map(); observed.put("observedApkIdentities", apks);
        Map<String, Object> targetApk = map(), testApk = map(); apks.put("target", targetApk); apks.put("test", testApk);
        installed(target.applicationInfo, request.get("targetApkSha256"), targetApk);
        installed(test.applicationInfo, request.get("testApkSha256"), testApk);
        InitialProtocol.require(target.applicationInfo.splitSourceDirs == null || target.applicationInfo.splitSourceDirs.length == 0, "target-split-apk");
        InitialProtocol.require(test.applicationInfo.splitSourceDirs == null || test.applicationInfo.splitSourceDirs.length == 0, "test-split-apk");
        try (ZipFile zip = new ZipFile(target.applicationInfo.sourceDir)) {
            int count = 0; Set<String> names = new java.util.HashSet<String>();
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) { String name = all.nextElement().getName(); InitialProtocol.require(names.add(name), "target-duplicate-entry"); if (ReadbackEvidence.isDexEntry(name)) count++; }
            apks.put("observedDexCount", count); InitialProtocol.require(count == 3, "target-dex-count");
            for (String name : new String[] {"classes.dex", "classes2.dex", "classes3.dex"}) {
                Map<String, Object> identity = map(); apks.put(name, identity); identity.put("entry", name);
                ZipEntry entry = zip.getEntry(name); InitialProtocol.require(entry != null && !entry.isDirectory(), "target-dex-missing");
                try (InputStream input = zip.getInputStream(entry)) { ReadbackFiles.digest(input, identity); }
                boolean hooked=ScenePlan.motionMode(request)!=null && !"bare".equals(ScenePlan.motionMode(request));
                String main=hooked?"44166a4b4d6a4a67c8a71ddf30dbdc1236f645b6d8daf81ab7fd222285865281":MAIN_DEX;
                String third=hooked?"d24e211373d41f99b6f0e8d21173dee0098a37b6ac65d95111a55c735e4bea6d":"7db89ce83c3ceccb4ee9194f31e14876f588d4122673ec95b3dbf869eadcacbf";
                boolean matches = ("classes.dex".equals(name) ? main : "classes2.dex".equals(name) ? HELPER_DEX : third).equals(identity.get("sha256"));
                identity.put("matchesExpected", matches); InitialProtocol.require(matches, "target-dex-drift");
            }
        }
    }

    private String signer(PackageInfo info, Map<String, Object> raw) {
        InitialProtocol.require(info.signingInfo != null, "installed-signing-info-missing");
        raw.put("multipleSigners", info.signingInfo.hasMultipleSigners());
        Signature[] signers = info.signingInfo.getApkContentsSigners(), history = info.signingInfo.getSigningCertificateHistory();
        List<Object> hashes = new ArrayList<Object>(); raw.put("apkSignerSha256", hashes);
        if (signers != null) for (Signature signature : signers) hashes.add(signature == null ? null : InitialProtocol.sha256(signature.toByteArray()));
        raw.put("historyCount", history == null ? null : history.length);
        InitialProtocol.require(!info.signingInfo.hasMultipleSigners() && signers != null && signers.length == 1 && signers[0] != null
                && history != null && history.length == 1, "installed-signing-history");
        return (String) hashes.get(0);
    }

    private void installed(ApplicationInfo app, String expected, Map<String, Object> raw) throws Exception {
        InitialProtocol.require(app != null && app.sourceDir != null, "installed-source-missing");
        raw.put("uid", app.uid); raw.put("path", app.sourceDir);
        ReadbackFiles.readFile(Paths.get(app.sourceDir), raw, null, expected);
    }

    private void seeds(Path files, Path root, List<Object> rows) throws Exception {
        for (int i = 0; i < SEEDS.length; i++) {
            Path literal = root.resolve(SEEDS[i]); List<Object> parents = new ArrayList<Object>();
            Map<String, Object> r = map(); rows.add(r); r.put("literal", literal.toString()); r.put("parentChecks", parents);
            ReadbackFiles.fixedParents(files, literal.getParent(), parents);
            ReadbackFiles.readFile(literal, r, SEED_LENGTHS[i], SEED_HASHES[i]);
        }
    }

    private Map<String, Object> cleanupActivities(final Map<String, Object> outcome) throws Throwable {
        try{return cleanupActivitiesBody(outcome);}
        finally{synchronized(motionUiErrors){outcome.put("motionUiErrors",errors(motionUiErrors,"motion-ui-lifecycle"));}}
    }
    private Map<String, Object> cleanupActivitiesBody(final Map<String, Object> outcome) throws Throwable {
        motionUiWindow.close();
        launchWindow.close();
        outcome.put("launcherRequested", launcherRequested); outcome.put("observedActivityCount", activities.size());
        outcome.put("remainingCurrentPid", Process.myPid()); outcome.put("processExitProven", false);
        if (activities.isEmpty()) {
            outcome.put("attempted", false); outcome.put("completed", false);
            outcome.put("skipReason", "no-observed-owned-target-activity");
            synchronized (lateLaunchErrors) { outcome.put("lateLaunchErrors", errors(lateLaunchErrors, "late-launch-cleanup")); }
            return outcome;
        }
        final CountDownLatch finished = new CountDownLatch(1);
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
        outcome.put("cleanupOperationsErrors", new ArrayList<Object>());
        outcome.put("cleanupTimeoutError", null);
        boolean posted = new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() {
            try {
                List<TrialLifecycle.Action> actions = new ArrayList<TrialLifecycle.Action>();
                actions.add(new TrialLifecycle.Action() { @Override public void run() throws Throwable { if (original != null) original.closeUpdateDialogs(); } });
                actions.add(new TrialLifecycle.Action() { @Override public void run() throws Throwable { if (original != null) outcome.put("updateDialogCleanup", original.updateDialogStatus()); } });
                synchronized (activities) { for (final Activity activity : activities) {
                    actions.add(new TrialLifecycle.Action() { @Override public void run() { if (!activity.isDestroyed()) activity.finish(); } });
                } }
                TrialLifecycle.cleanupActions(errors, actions.toArray(new TrialLifecycle.Action[actions.size()]));
            } finally { finished.countDown(); }
        }});
        outcome.put("mainPosted", posted); InitialProtocol.require(posted, "cleanup-main-post-failed");
        long end = Math.min(startNanos + TimeUnit.SECONDS.toNanos(90), SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(10));
        boolean callsCompleted = finished.await(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(end - SystemClock.elapsedRealtimeNanos())), TimeUnit.MILLISECONDS);
        outcome.put("finishCallsCompleted", callsCompleted);
        List<Throwable> operationErrors;
        synchronized (errors) { operationErrors = new ArrayList<Throwable>(errors); }
        outcome.put("cleanupOperationsErrors", errors(operationErrors, "cleanup-operation"));
        Throwable timeout = callsCompleted ? null : new IllegalArgumentException("cleanup-main-timeout");
        if (timeout != null) outcome.put("cleanupTimeoutError", error(timeout, "cleanup-main-timeout"));
        if (!operationErrors.isEmpty()) throw operationErrors.get(0);
        if (timeout != null) throw timeout;
        synchronized (lateLaunchErrors) {
            outcome.put("lateLaunchErrors", errors(lateLaunchErrors, "late-launch-cleanup"));
            if (!lateLaunchErrors.isEmpty()) throw lateLaunchErrors.get(0);
        }
        for (;;) {
            boolean destroyed = !motionUiWindow.awaitingCreation(); List<Object> states = new ArrayList<Object>();
            synchronized (activities) { for (Activity activity : activities) {
                Map<String, Object> r = map(); r.put("class", activity.getClass().getName()); r.put("isFinishing", activity.isFinishing());
                r.put("isDestroyed", activity.isDestroyed()); states.add(r); destroyed &= activity.isDestroyed();
            }}
            outcome.put("activityStates", states); if (destroyed) break;
            InitialProtocol.require(SystemClock.elapsedRealtimeNanos() < end, "cleanup-destroy-timeout"); SystemClock.sleep(50);
        }
        if (original != null) {
            Object engine = original.engine(); boolean running = engine != null && original.running(engine);
            outcome.put("originalThreadRunning", running); InitialProtocol.require(!running, "cleanup-original-thread-running");
            Map<String, Object> dialogs = original.updateDialogStatus(); outcome.put("updateDialogCleanup", dialogs);
            InitialProtocol.require(Boolean.TRUE.equals(dialogs.get("closed")) && Integer.valueOf(0).equals(dialogs.get("pendingCount"))
                && dialogs.get("firstError") == null, "cleanup-fixed-update-dialog-incomplete");
        }
        synchronized (lateLaunchErrors) {
            outcome.put("lateLaunchErrors", errors(lateLaunchErrors, "late-launch-cleanup"));
            if (!lateLaunchErrors.isEmpty()) throw lateLaunchErrors.get(0);
        }
        if(motionCaptured && motionDriver!=null){
            if(motionUiError!=null)throw motionUiError;
            motionDriver.checkFinalStable();outcome.put("motionFinalCheck",motionDriver.finalFacts());
        }
        if(motionUiError!=null)throw motionUiError;
        deadline(90000); outcome.put("completed", true); return outcome;
    }

    private void deadline(long millis) { InitialProtocol.require(SystemClock.elapsedRealtimeNanos() - startNanos <= TimeUnit.MILLISECONDS.toNanos(millis), "session-deadline:" + trial.stage); }
    private void emit(final String kind, final Map<String, Object> payload) throws Throwable {
        trial.transport(new TrialLifecycle.Action() { @Override public void run() { sendStatus(1, record(kind, payload)); } });
        trial.emitted(kind); if (!"failure".equals(kind)) lastCompleted = kind;
    }
    private Bundle record(String kind, Map<String, Object> payload) {
        Map<String, Object> r = map(); r.put("schemaVersion", 1); r.put("profile", request.get("profile")); r.put("runId", request.get("runId"));
        r.put("requestId", request.get("requestId")); r.put("sequence", stream.hashes().size()); r.put("kind", kind);
        r.put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos()); r.put("utc", Instant.now().toString());
        r.put("targetPid", Process.myPid()); r.put("targetUid", Process.myUid()); r.put("processName", Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : null);
        r.put("targetPackage", TARGET); r.put("testPackage", TEST); r.put("observedApkIdentities", observed.get("observedApkIdentities"));
        r.put("observedSignerSha256", observed.get("observedSignerSha256")); r.put("payload", payload);
        Bundle result = new Bundle(); result.putString("r12Record", stream.append(kind, r)); return result;
    }

    private List<Object> errors(List<Throwable> errors, String stage) {
        List<Object> r = new ArrayList<Object>(); synchronized (errors) { for (Throwable error : errors) r.add(error(error, stage)); } return r;
    }

    private Map<String, Object> error(Throwable error, String stage) {
        Map<String, Object> r = map(); r.put("stage", stage); List<Object> causes = new ArrayList<Object>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 8; depth++) {
            if (!seen.add(cursor)) { r.put("causeCycle", true); break; }
            Map<String, Object> item = map(); item.put("type", cursor.getClass().getName()); item.put("message", cursor.getMessage());
            if (cursor.getClass().getName().equals("com.rustedwarfare.reforge.reference.io.FatalLatch$Failure")) {
                try {
                    Method code = cursor.getClass().getMethod("code");
                    InitialProtocol.require(code.getReturnType() == String.class && Modifier.isPublic(code.getModifiers()) && !Modifier.isStatic(code.getModifiers())
                            && cursor.getClass().getClassLoader() == getTargetContext().getClassLoader(), "fatal-code-public-descriptor");
                    item.put("fatalCode", code.invoke(cursor));
                }
                catch (Exception failure) { item.put("fatalCodeReadError", failure.getClass().getName()); }
            }
            causes.add(item); cursor = cursor instanceof InvocationTargetException ? ((InvocationTargetException) cursor).getTargetException() : cursor.getCause();
        }
        r.put("causes", causes); r.put("causeDepthLimitReached", cursor != null && causes.size() == 8); return r;
    }

    private static Map<String, Object> map() { return new LinkedHashMap<String, Object>(); }
}
