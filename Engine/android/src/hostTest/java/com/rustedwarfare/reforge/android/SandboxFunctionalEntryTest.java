package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.rustedwarfare.reforge.client.SandboxConfiguration;
import com.rustedwarfare.reforge.client.SandboxLaunchState;
import com.rustedwarfare.reforge.client.SandboxMode;
import com.rustedwarfare.reforge.client.SandboxOptions;
import java.nio.file.Path;

/** Explicit JVM-only contract checks; the runner separately compiles the TEST-only helper. */
public final class SandboxFunctionalEntryTest {
    private static JsonValue json(String text) { return new JsonReader().parse(text); }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void rejected(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid public record accepted");
    }
    private static void generation() {
        // Exercise the actual shared public state machine, without changing its internals.
        var state = new SandboxLaunchState(new SandboxConfiguration(Path.of(".").toAbsolutePath(),
            new SandboxOptions(SandboxMode.MEMORY, false)));
        long first = state.start().getGeneration();
        require(state.complete(first), "First load rejected");
        long second = state.reopen().getGeneration();
        require(state.complete(second), "Reopen rejected");
        state.exit();
        long third = state.start().getGeneration();
        require(first == 1 && second == 2 && third == 4, "Shared lifecycle contract changed");
        require(SandboxFunctionalOracle.newGeneration(json("{kind:load-request,generation:1}"), 0) == first,
            "Initial generation rejected");
        require(SandboxFunctionalOracle.newGeneration(json("{kind:load-request,generation:2}"), first) == second,
            "Reopen generation rejected");
        require(SandboxFunctionalOracle.newGeneration(json("{kind:load-request,generation:4}"), second) == third,
            "Start after Exit must accept the actual new generation");
        rejected(() -> SandboxFunctionalOracle.newGeneration(json("{kind:load-request,generation:2}"), second));
        rejected(() -> SandboxFunctionalOracle.newGeneration(json("{kind:load-request,generation:1}"), second));
        rejected(() -> SandboxFunctionalOracle.newGeneration(json("{kind:load-request,generation:-1}"), second));
        rejected(() -> SandboxFunctionalOracle.newGeneration(json("{kind:observation,generation:4}"), second));
        System.out.println("PASS real Start/Reopen/Exit/Start generations and stale-record rejection");
    }
    private static void target() {
        // Recorded viewport 1620x1080 over UI 960x640: one physical pixel is 16/27 world units.
        // Requested (110,150) needs fractional screen (376.125,489.375); integer input truncates it.
        JsonValue quantized = json("{target:{x:109.92591857910156,y:149.77777099609375}}");
        SandboxFunctionalOracle.target(quantized, 110, 150, 16.0 / 27, 16.0 / 27);
        SandboxFunctionalOracle.target(json("{target:{x:110,y:150}}"), 110, 150, 16.0 / 27, 16.0 / 27);
        rejected(() -> SandboxFunctionalOracle.target(json("{target:{x:109,y:150}}"), 110, 150, 16.0 / 27, 16.0 / 27));
        rejected(() -> SandboxFunctionalOracle.target(json("{target:{x:110,y:149}}"), 110, 150, 16.0 / 27, 16.0 / 27));
        rejected(() -> SandboxFunctionalOracle.target(quantized, 110, 150, 0, 1));
        rejected(() -> SandboxFunctionalOracle.target(quantized, 110, 150, 1, Double.POSITIVE_INFINITY));
        rejected(() -> SandboxFunctionalOracle.target(quantized, Double.NaN, 150, 1, 1));
        rejected(() -> SandboxFunctionalOracle.target(json("{target:{x:1e999,y:150}}"), 110, 150, 1, 1));
        System.out.println("PASS actual integer touch footprint; wrong targets and invalid geometry rejected");
    }
    private static void reroute() {
        require(!SandboxFunctionalOracle.detour(json("{pathPoints:[{x:349.9,y:109.77}]}"), 109.77, 16.0 / 27),
            "A quantized direct goal must not count as a detour");
        require(!SandboxFunctionalOracle.detour(json("{pathPoints:[{x:250,y:109.9},{x:349.9,y:109.77}]}"), 109.77, 16.0 / 27),
            "Splitting a straight route within one input pixel must not count as a detour");
        require(SandboxFunctionalOracle.detour(json("{pathPoints:[{x:310,y:70},{x:349.9,y:109.77}]}"), 109.77, 16.0 / 27),
            "An actual intermediate bend must count as a detour");
        require(!SandboxFunctionalOracle.detour(json("{pathPoints:[]}"), 109.77, 16.0 / 27),
            "No route must not count as a detour");
        System.out.println("PASS real intermediate detour; direct quantized target cannot impersonate a reroute");
    }
    private static String initialSample() {
        return "{scenarioId:p002-knowledge,playerId:p1,stepIndex:0,simulationTime:0,units:["
            + "{unitId:u1,ownerPlayerId:p1,position:{x:110,y:110},speed:0,angularVelocity:0,moveTarget:null,pathPoints:[]}]}";
    }
    private static void initial() {
        String sample = initialSample();
        SandboxFunctionalOracle.initial(json(sample), "MEMORY", false);
        rejected(() -> SandboxFunctionalOracle.initial(json(sample.replace("speed:0", "speed:1")), "MEMORY", false));
        rejected(() -> SandboxFunctionalOracle.initial(json(sample.replace("angularVelocity:0", "angularVelocity:1")), "MEMORY", false));
        rejected(() -> SandboxFunctionalOracle.initial(json(sample.replace("stepIndex:0", "stepIndex:1")), "MEMORY", false));
        rejected(() -> SandboxFunctionalOracle.initial(json(sample.replace("simulationTime:0", "simulationTime:1")), "MEMORY", false));
        rejected(() -> SandboxFunctionalOracle.initial(json(sample.replace("moveTarget:null", "moveTarget:{x:200,y:200}")), "MEMORY", false));
        rejected(() -> SandboxFunctionalOracle.initial(json(sample.replace("pathPoints:[]", "pathPoints:[{x:200,y:200}]")), "MEMORY", false));
        System.out.println("PASS clean new match; retained time, motion, target and route rejected");
    }
    private static void retentionParameters() {
        for (String mode : new String[]{"MEMORY", "HARDCORE"}) {
            var config = SandboxFunctionalOracle.parameters(java.util.Map.of(
                "mode", mode, "shared", "false", "exercise", "retention"));
            require(config.mode.equals(mode) && !config.shared && config.exercise.equals("retention"),
                "Finite retention configuration rejected");
            rejected(() -> SandboxFunctionalOracle.parameters(java.util.Map.of(
                "mode", mode, "shared", "true", "exercise", "retention")));
        }
        rejected(() -> SandboxFunctionalOracle.parameters(java.util.Map.of(
            "mode", "NO_FOG", "shared", "false", "exercise", "retention")));
        rejected(() -> SandboxFunctionalOracle.parameters(java.util.Map.of(
            "mode", "MEMORY", "shared", "false", "exercise", "retention", "cell", "155")));
        System.out.println("PASS finite retention parameters; unsupported modes, sharing and extra controls rejected");
    }
    private static final String KNOWN = "{visible:true,gid:1,blocked:false}";
    private static final String REMEMBERED = "{visible:false,gid:1,blocked:false}";
    private static final String FORGOTTEN = "{visible:false,gid:null,blocked:null}";
    private static JsonValue retentionRow(long sequence, long step, double time, String cell, boolean initial) {
        String[] cells = new String[600];
        java.util.Arrays.fill(cells, "{visible:false,gid:null,blocked:null}");
        cells[155] = cell;
        return json("{kind:observation,generation:1,sequence:" + sequence + ",phase:" + (initial ? "initial" : "step")
            + ",sample:{worldId:w,scenarioId:p002-knowledge,playerId:p1,stepIndex:" + step
            + ",simulationTime:" + time + ",knowledge:[" + String.join(",", cells) + "],units:["
            + "{unitId:u1,position:{x:110,y:" + (initial ? "110" : "120") + "}}]}}");
    }
    private static SandboxFunctionalOracle.Retention retentionChecker(String mode) {
        return new SandboxFunctionalOracle.Retention(retentionRow(10, 0, 0, KNOWN, true), mode);
    }
    private static SandboxFunctionalOracle.Retention threeLost(String mode) {
        var check = retentionChecker(mode);
        String cell = mode.equals("MEMORY") ? REMEMBERED : FORGOTTEN;
        check.accept(retentionRow(11, 0, 1, cell, false));
        require(!check.ready(), "One step must not pass");
        check.accept(retentionRow(12, 1, 2, cell, false));
        require(!check.ready(), "Two steps must not pass");
        check.accept(retentionRow(13, 2, 3, cell, false));
        require(check.ready(), "Three distinct lost observations with real movement must pass");
        return check;
    }
    private static void retentionRecords() {
        threeLost("MEMORY");
        threeLost("HARDCORE");
        rejected(() -> retentionChecker("NO_FOG"));
        for (String bad : new String[]{FORGOTTEN, "{visible:false,gid:2,blocked:false}",
                "{visible:false,gid:1,blocked:true}", "{visible:false,gid:1}",
                "{visible:false,gid:1,blocked:\"false\"}", "{visible:\"false\",gid:1,blocked:false}"}) {
            var check = retentionChecker("MEMORY");
            rejected(() -> check.accept(retentionRow(11, 0, 1, bad, false)));
            check.accept(retentionRow(12, 1, 2, REMEMBERED, false));
            check.accept(retentionRow(13, 2, 3, REMEMBERED, false));
            check.accept(retentionRow(14, 3, 4, REMEMBERED, false));
            require(!check.ready(), "A first bad lost observation must remain failed after later green records");
        }
        rejected(() -> retentionChecker("HARDCORE").accept(retentionRow(11, 0, 1, REMEMBERED, false)));
        for (String bad : new String[]{"{visible:false,gid:null}", "{visible:false,gid:\"null\",blocked:null}",
                "{visible:false,gid:null,blocked:false}"})
            rejected(() -> retentionChecker("HARDCORE").accept(retentionRow(11, 0, 1, bad, false)));
        // The same accept boundary is used for every row, including same-batch and close-tail records.
        for (String phase : new String[]{"same batch after ready", "close tail after ready"}) {
            var late = threeLost("MEMORY");
            rejected(() -> late.accept(retentionRow(14, 3, 4, FORGOTTEN, false)));
            require(!late.ready(), phase + " must not hide a late retention failure");
        }
        var reset = threeLost("HARDCORE");
        reset.accept(retentionRow(14, 3, 4, KNOWN, false));
        require(!reset.ready(), "Visibility must reset consecutive loss");
        reset.accept(retentionRow(15, 4, 5, FORGOTTEN, false));
        reset.accept(retentionRow(16, 5, 6, FORGOTTEN, false));
        require(!reset.ready(), "Two samples after visibility must not count as three");
        reset.accept(retentionRow(17, 6, 7, FORGOTTEN, false));
        require(reset.ready(), "Three new consecutive lost samples must pass");
        rejected(() -> threeLost("MEMORY").accept(retentionRow(13, 3, 4, REMEMBERED, false)));
        rejected(() -> threeLost("MEMORY").accept(retentionRow(14, 2, 4, REMEMBERED, false)));
        rejected(() -> threeLost("MEMORY").accept(retentionRow(14, 1, 4, REMEMBERED, false)));
        rejected(() -> threeLost("MEMORY").accept(retentionRow(14, 3, 3, REMEMBERED, false)));
        var nonfinite = retentionRow(11, 0, 1, REMEMBERED, false);
        nonfinite.get("sample").get("units").get(0).get("position").get("x").set(Double.NaN, null);
        rejected(() -> retentionChecker("MEMORY").accept(nonfinite));
        var foreign = retentionRow(11, 0, 1, REMEMBERED, false);
        foreign.get("sample").get("worldId").set("other");
        rejected(() -> retentionChecker("MEMORY").accept(foreign));
        var shortMap = retentionRow(11, 0, 1, REMEMBERED, false);
        shortMap.get("sample").get("knowledge").remove(599);
        rejected(() -> retentionChecker("MEMORY").accept(shortMap));
        var unmoved = retentionChecker("MEMORY");
        for (int i = 0; i < 3; i++) {
            var row = retentionRow(11 + i, i, i + 1, REMEMBERED, false);
            row.get("sample").get("units").get(0).get("position").get("y").set(110L, null);
            unmoved.accept(row);
        }
        require(!unmoved.ready(), "Lost samples without observed movement must not pass");
        System.out.println("PASS ordered retention, first/late failures, strict fields, visible reset and finite movement");
    }
    private static void permission() {
        String selected = "{type:SelectAction,unitId:u2,actorPlayerId:p1,result:{accepted:true}}";
        String denied = "{type:MoveAction,unitId:u2,actorPlayerId:p1,result:{accepted:false,code:not-owner}}";
        SandboxFunctionalOracle.foreignAction(json(selected), "SelectAction", "u2");
        SandboxFunctionalOracle.foreignAction(json(denied), "MoveAction", "u2");
        SandboxFunctionalOracle.foreignAction(json(denied.replace("MoveAction", "StopAction")), "StopAction", "u2");
        rejected(() -> SandboxFunctionalOracle.foreignAction(json(denied.replace("accepted:false", "accepted:true")), "MoveAction", "u2"));
        rejected(() -> SandboxFunctionalOracle.foreignAction(json(denied.replace("not-owner", "unit-not-visible")), "MoveAction", "u2"));
        rejected(() -> SandboxFunctionalOracle.foreignAction(json(denied.replace("actorPlayerId:p1", "actorPlayerId:p2")), "MoveAction", "u2"));
        rejected(() -> SandboxFunctionalOracle.foreignAction(json(denied.replace("unitId:u2", "unitId:u3")), "MoveAction", "u2"));
        rejected(() -> SandboxFunctionalOracle.foreignAction(json(denied), "StopAction", "u2"));
        rejected(() -> SandboxFunctionalOracle.foreignAction(json(selected.replace("accepted:true", "accepted:false")), "SelectAction", "u2"));
        rejected(() -> SandboxFunctionalOracle.foreignAction(json(denied.replace("accepted:false", "accepted:\"false\"")), "MoveAction", "u2"));
        for (String id : new String[]{"u2", "u3"}) {
            String foreign = "{worldId:w,scenarioId:p002-knowledge,playerId:p1,units:[{unitId:" + id + ",ownerPlayerId:p" + id.substring(1)
                + ",typeId:tank,position:{x:510,y:310},heading:0,speed:0,angularVelocity:0,"
                + "moveTarget:null,pathPoints:[],movementState:idle}]}";
            SandboxFunctionalOracle.foreignAction(json(selected.replace("u2", id)), "SelectAction", id);
            SandboxFunctionalOracle.foreignAction(json(denied.replace("u2", id)), "MoveAction", id);
            SandboxFunctionalOracle.foreignUnchanged(json(foreign), json(foreign), id);
            for (String changed : new String[]{foreign.replace("x:510", "x:511"),
                    foreign.replace("speed:0", "speed:1"), foreign.replace("heading:0", "heading:1"),
                    foreign.replace("moveTarget:null", "moveTarget:{x:510,y:270}"),
                    foreign.replace("pathPoints:[]", "pathPoints:[{x:510,y:270}]")})
                rejected(() -> SandboxFunctionalOracle.foreignUnchanged(json(foreign), json(changed), id));
            rejected(() -> SandboxFunctionalOracle.foreignUnchanged(json(foreign), json(foreign.replace("worldId:w", "worldId:other")), id));
            rejected(() -> SandboxFunctionalOracle.foreignUnchanged(json(foreign), json(foreign.replace("playerId:p1", "playerId:p2")), id));
            var missing = json(foreign);
            missing.get("units").remove(0);
            rejected(() -> SandboxFunctionalOracle.foreignUnchanged(json(foreign), missing, id));
            var duplicate = json(foreign);
            duplicate.get("units").addChild(json(foreign).get("units").get(0));
            rejected(() -> SandboxFunctionalOracle.foreignUnchanged(json(foreign), duplicate, id));
            var action = json("{kind:action,generation:1,sequence:20}");
            var after = json("{kind:observation,phase:after-action,generation:1,sequence:21,sample:" + foreign + "}");
            require(!SandboxFunctionalOracle.foreignAfterAction(java.util.List.of(), action, json(foreign), id),
                "An empty after-action set must not pass");
            require(SandboxFunctionalOracle.foreignAfterAction(java.util.List.of(after), action, json(foreign), id),
                "Actual after-action observation rejected");
            after.get("generation").set(2L, null);
            require(!SandboxFunctionalOracle.foreignAfterAction(java.util.List.of(after), action, json(foreign), id),
                "Other generation cannot prove this action");
            after.get("generation").set(1L, null);
            after.get("sequence").set(19L, null);
            require(!SandboxFunctionalOracle.foreignAfterAction(java.util.List.of(after), action, json(foreign), id),
                "A prior observation cannot prove this action");
            // These are helper/first-error checks; Android cursor/closing wiring requires its own runtime evidence.
            for (boolean badFirst : new boolean[]{true, false}) {
                var failures = new SandboxFunctionalOracle.Failures();
                var good = json(foreign);
                var bad = json(foreign.replace("x:510", "x:511"));
                var samples = badFirst ? java.util.List.of(bad, good, good, good)
                    : java.util.List.of(good, good, good, bad);
                for (var sample : samples) {
                    try { SandboxFunctionalOracle.foreignUnchanged(json(foreign), sample, id); }
                    catch (RuntimeException failure) { failures.business(failure); }
                }
                require(failures.primary != null, "Early or late foreign state change must remain failed");
            }
        }
        System.out.println("PASS visible foreign selection, not-owner identity/result, and unchanged public unit state");
    }
    private static void unreachableParameters() {
        for (String shared : new String[]{"false", "true"}) {
            var config = SandboxFunctionalOracle.parameters(java.util.Map.of(
                "mode", "NO_FOG", "shared", shared, "exercise", "unreachable"));
            require(config.exercise.equals("unreachable") && config.shared == shared.equals("true"),
                "Fixed unreachable parameters rejected");
        }
        for (String mode : new String[]{"MEMORY", "HARDCORE"})
            rejected(() -> SandboxFunctionalOracle.parameters(java.util.Map.of(
                "mode", mode, "shared", "false", "exercise", "unreachable")));
        System.out.println("PASS fixed NoFog unreachable parameters; knowledge-mode shortcut rejected");
    }
    private static JsonValue unreachableRow(long seq, long step, double time, String phase, double y, double speed, boolean moving) {
        return json("{kind:observation,generation:1,sequence:" + seq + ",phase:" + phase
            + ",sample:{worldId:w,scenarioId:p002-knowledge,playerId:p1,stepIndex:" + step + ",simulationTime:" + time
            + ",events:[],units:[{unitId:u1,ownerPlayerId:p1,position:{x:110,y:" + y + "},speed:" + speed
            + ",angularVelocity:0,moveTarget:" + (moving ? "{x:110,y:290}" : "null")
            + ",pathPoints:" + (moving ? "[{x:110,y:290}]" : "[]") + "}]}}");
    }
    private static JsonValue startedMove() {
        return json("{kind:action,generation:1,sequence:10,type:MoveAction,actorPlayerId:p1,unitId:u1,"
            + "target:{x:110,y:290},result:{accepted:true,events:[{kind:movement-started,unitId:u1}]}}");
    }
    private static JsonValue cancelledMove() {
        return json("{kind:action,generation:1,sequence:21,type:MoveAction,actorPlayerId:p1,unitId:u1,"
            + "target:{x:450,y:110},result:{accepted:true,events:[{kind:movement-cancelled,unitId:u1},{kind:path-unreachable,unitId:u1}]}}");
    }
    private static SandboxFunctionalOracle.Unreachable unreachableChecker() {
        return new SandboxFunctionalOracle.Unreachable(startedMove(),
            unreachableRow(20, 10, 11, "before-action", 120, 1, true), cancelledMove());
    }
    private static SandboxFunctionalOracle.Unreachable stoppedUnreachable() {
        var check = unreachableChecker();
        check.accept(unreachableRow(22, 10, 11, "after-action", 120, 1, false));
        for (int i = 0; i < 3; i++) {
            check.accept(unreachableRow(23 + i, 11 + i, 12 + i, "step", 120.5, 0, false));
            require(check.ready() == (i == 2), "Unreachable requires three actual stopped steps");
        }
        return check;
    }
    private static void unreachableRecords() {
        stoppedUnreachable();
        var wrongResult = cancelledMove();
        wrongResult.get("result").get("events").get(1).get("kind").set("movement-started");
        rejected(() -> new SandboxFunctionalOracle.Unreachable(startedMove(),
            unreachableRow(20, 10, 11, "before-action", 120, 1, true), wrongResult));
        rejected(() -> new SandboxFunctionalOracle.Unreachable(startedMove(),
            unreachableRow(20, 10, 11, "before-action", 120, 0, false), cancelledMove()));
        rejected(() -> new SandboxFunctionalOracle.Unreachable(startedMove(),
            unreachableRow(20, 10, 11, "before-action", 110, 1, true), cancelledMove()));
        var wrongGeneration = cancelledMove();
        wrongGeneration.get("generation").set(2L, null);
        rejected(() -> new SandboxFunctionalOracle.Unreachable(startedMove(),
            unreachableRow(20, 10, 11, "before-action", 120, 1, true), wrongGeneration));
        rejected(() -> unreachableChecker().accept(unreachableRow(22, 11, 12, "step", 120, 0, false)));
        rejected(() -> unreachableChecker().accept(unreachableRow(22, 10, 11, "after-action", 121, 1, false)));
        rejected(() -> unreachableChecker().accept(unreachableRow(22, 10, 11, "after-action", 120, 1, true)));
        rejected(() -> unreachableChecker().accept(unreachableRow(22, 11, 12, "after-action", 120, 1, false)));
        var late = stoppedUnreachable();
        rejected(() -> late.accept(unreachableRow(26, 14, 15, "step", 120.5, 0, true)));
        late.accept(unreachableRow(27, 15, 16, "step", 120.5, 0, false));
        require(!late.ready(), "Late restored target must remain failed");
        rejected(() -> stoppedUnreachable().accept(unreachableRow(25, 14, 15, "step", 120.5, 0, false)));
        rejected(() -> stoppedUnreachable().accept(unreachableRow(26, 13, 14, "step", 120.5, 0, false)));
        var illegal = unreachableRow(26, 14, 15, "step", 120.5, 0, false);
        illegal.get("sample").get("units").get(0).get("position").get("x").set(280L, null);
        rejected(() -> stoppedUnreachable().accept(illegal));
        var nonfinite = unreachableRow(26, 14, 15, "step", 120.5, 0, false);
        nonfinite.get("sample").get("units").get(0).get("speed").set(Double.NaN, null);
        rejected(() -> stoppedUnreachable().accept(nonfinite));
        var arrived = unreachableRow(26, 14, 15, "before-close", 120.5, 0, false);
        arrived.get("sample").get("events").addChild(json("{kind:movement-arrived,unitId:u1}"));
        rejected(() -> stoppedUnreachable().accept(arrived));
        var postStopMove = stoppedUnreachable();
        rejected(() -> postStopMove.accept(unreachableRow(26, 14, 15, "step", 121, 0, false)));
        postStopMove.accept(unreachableRow(27, 15, 16, "step", 120.5, 0, false));
        require(!postStopMove.ready(), "Moving after three stable steps cannot be cured by a later good sample");
        rejected(() -> stoppedUnreachable().accept(unreachableRow(26, 13, 14, "before-close", 120.5, 1, false)));
        var restoredPath = unreachableRow(26, 14, 15, "step", 120.5, 0, false);
        restoredPath.get("sample").get("units").get(0).get("pathPoints").addChild(json("{x:450,y:110}"));
        rejected(() -> stoppedUnreachable().accept(restoredPath));
        var wrongOwner = unreachableRow(22, 10, 11, "after-action", 120, 1, false);
        wrongOwner.get("sample").get("units").get(0).get("ownerPlayerId").set("p2");
        rejected(() -> unreachableChecker().accept(wrongOwner));
        var duplicate = unreachableRow(22, 10, 11, "after-action", 120, 1, false);
        duplicate.get("sample").get("units").addChild(
            unreachableRow(22, 10, 11, "after-action", 120, 1, false).get("sample").get("units").get(0));
        rejected(() -> unreachableChecker().accept(duplicate));
        var missing = unreachableRow(22, 10, 11, "after-action", 120, 1, false);
        missing.get("sample").get("units").remove(0);
        rejected(() -> unreachableChecker().accept(missing));
        System.out.println("PASS active cancellation, required immediate observation, no restored route, safe stopped position and late failures");
    }
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("One test group required");
        switch (args[0]) {
            case "generation": generation(); break;
            case "target": target(); break;
            case "reroute": reroute(); break;
            case "initial": initial(); break;
            case "retention": retentionParameters(); retentionRecords(); break;
            case "permission": permission(); break;
            case "unreachable": unreachableParameters(); unreachableRecords(); break;
            case "all": generation(); target(); reroute(); initial(); retentionParameters(); retentionRecords(); permission(); unreachableParameters(); unreachableRecords(); break;
            default: throw new IllegalArgumentException("Unknown test group");
        }
    }
}
