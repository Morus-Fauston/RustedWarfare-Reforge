package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Constructed records test the oracle, never Android lifecycle execution. */
public final class SandboxPauseOracleTest {
    private static JsonValue json(String text) { return new JsonReader().parse(text); }
    private static String observation(long step, double y) {
        return "\"kind\":\"observation\",\"generation\":1,\"phase\":\"step\",\"sample\":{"
            + "\"worldId\":\"w1\",\"playerId\":\"p1\",\"scenarioId\":\"p002-knowledge\","
            + "\"stepIndex\":" + step + ",\"simulationTime\":" + (step+1) + ",\"units\":["
            + "{\"unitId\":\"u1\",\"ownerPlayerId\":\"p1\",\"position\":{\"x\":110,\"y\":" + y + "},"
            + "\"speed\":1.1,\"angularVelocity\":0,\"moveTarget\":{\"x\":110,\"y\":290},"
            + "\"pathPoints\":[{\"x\":110,\"y\":290}]}]}";
    }
    private static List<String> good() {
        return new ArrayList<>(List.of("\"kind\":\"launch\"", "\"kind\":\"load-request\",\"generation\":1",
            "\"kind\":\"load-ready\",\"generation\":1", observation(10,120),
            "\"kind\":\"presentation-released\"", "\"kind\":\"pause\"", "\"kind\":\"resume\"",
            observation(11,121.1), observation(12,122.2), observation(13,123.3)));
    }
    private static byte[] bytes(List<String> rows) {
        StringBuilder text = new StringBuilder();
        for (int i=0;i<rows.size();i++) text.append("{\"sequence\":").append(i).append(',').append(rows.get(i)).append("}\n");
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static long boundary(List<String> rows, int count) { return bytes(rows.subList(0,count)).length; }
    private static int verify(List<String> rows, int a, int b) {
        return SandboxPauseOracle.verify(bytes(rows),1,json("{x:110,y:290}"),boundary(rows,a),boundary(rows,b),20,21,0);
    }
    private static void rejected(Runnable run) {
        try { run.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid pause evidence accepted");
    }
    private static void permission() {
        SandboxResumePermission good=new SandboxResumePermission();
        good.pause(100); good.arm(1_000_000_100); good.resume(1_000_000_101);
        if (good.pauseNanos()!=100 || good.requestNanos()!=1_000_000_100 || good.resumeNanos()!=1_000_000_101)
            throw new AssertionError("Resume timestamps lost");
        SandboxResumePermission early=new SandboxResumePermission();
        early.pause(100);
        // Models a resume in the last-poll-to-arm scheduling gap.
        rejected(() -> early.resume(1_000_000_100));
        rejected(() -> early.arm(1_000_000_200)); // A later legitimate request cannot cure it.
        SandboxResumePermission shortHold=new SandboxResumePermission(); shortHold.pause(100);
        rejected(() -> shortHold.arm(1_000_000_099));
        rejected(() -> new SandboxResumePermission().arm(1_000_000_100));
        SandboxResumePermission repeated=new SandboxResumePermission(); repeated.pause(100); repeated.arm(1_000_000_100);
        rejected(() -> repeated.arm(1_000_000_101));
        SandboxResumePermission backwards=new SandboxResumePermission(); backwards.pause(100); backwards.arm(1_000_000_100);
        rejected(() -> backwards.resume(1_000_000_099));
        rejected(() -> good.resume(1_000_000_102));
    }
    public static void main(String[] args) {
        permission();
        for (String mode : List.of("NO_FOG","MEMORY","HARDCORE")) for (String shared : List.of("false","true")) {
            if (!SandboxFunctionalOracle.parameters(java.util.Map.of("mode",mode,"shared",shared,"exercise","pause")).exercise.equals("pause"))
                throw new AssertionError("Finite pause configuration rejected");
        }
        List<String> rows=good();
        if (verify(rows,7,7)!=3) throw new AssertionError("Valid zero-delta resume rejected");
        rejected(() -> SandboxPauseOracle.verify(bytes(rows),1,json("{x:110,y:290}"),
            boundary(rows,7),boundary(rows,7),20,21,1.0));
        rejected(() -> SandboxPauseOracle.verify(bytes(rows),1,json("{x:110,y:290}"),
            boundary(rows,7),boundary(rows,7),20,22,0));
        rejected(() -> SandboxPauseOracle.verify(bytes(rows),1,json("{x:110,y:290}"),
            boundary(rows,7)-1,boundary(rows,7),20,21,0));
        rejected(() -> verify(rows,7,8)); // A/B captures an actual first-frame tick.
        List<String> gap=good();gap.add(6,observation(11,121.1));
        rejected(() -> verify(gap,8,8)); // Background advancement.
        List<String> preA=good();preA.add(7,observation(11,121.1));
        rejected(() -> verify(preA,8,8)); // Resume-to-A gap.
        List<String> jumped=good();jumped.set(7,observation(12,122.2));
        rejected(() -> verify(jumped,7,7));
        List<String> teleported=good();teleported.set(7,observation(11,130));
        rejected(() -> verify(teleported,7,7));
        List<String> changed=good();changed.set(8,changed.get(8).replace("\"w1\"","\"w2\""));
        rejected(() -> verify(changed,7,7));
        List<String> target=good();target.set(8,target.get(8).replace("\"y\":290","\"y\":280"));
        rejected(() -> verify(target,7,7));
        List<String> owner=good();owner.set(8,owner.get(8).replace("\"ownerPlayerId\":\"p1\"","\"ownerPlayerId\":\"p2\""));
        rejected(() -> verify(owner,7,7));
        List<String> nonfinite=good();nonfinite.set(8,nonfinite.get(8).replace("\"speed\":1.1","\"speed\":1e999"));
        rejected(() -> verify(nonfinite,7,7));
        List<String> lostPath=good();lostPath.set(8,lostPath.get(8).replace("\"pathPoints\":[{\"x\":110,\"y\":290}]","\"pathPoints\":[]"));
        rejected(() -> verify(lostPath,7,7));
        List<String> time=good();time.set(8,time.get(8).replace("\"simulationTime\":13","\"simulationTime\":15"));
        rejected(() -> verify(time,7,7));
        List<String> stagnantTime=good();
        for (int i : new int[]{3,7,8,9}) stagnantTime.set(i,stagnantTime.get(i).replaceAll(
            "\"simulationTime\":[^,]+,", "\"simulationTime\":9007199254740992,"));
        rejected(() -> verify(stagnantTime,7,7));
        List<String> overflow=good();
        overflow.set(3,observation(Long.MAX_VALUE,120).replaceAll("\"simulationTime\":[^,]+,","\"simulationTime\":11,"));
        overflow.set(7,observation(Long.MIN_VALUE,121.1).replaceAll("\"simulationTime\":[^,]+,","\"simulationTime\":12,"));
        overflow.set(8,observation(Long.MIN_VALUE+1,122.2).replaceAll("\"simulationTime\":[^,]+,","\"simulationTime\":13,"));
        overflow.set(9,observation(Long.MIN_VALUE+2,123.3).replaceAll("\"simulationTime\":[^,]+,","\"simulationTime\":14,"));
        rejected(() -> verify(overflow,7,7));
        List<String> negativeBaseline=good();
        negativeBaseline.set(3,observation(-1,120));
        for (int i=7;i<10;i++) negativeBaseline.set(i,observation(i-7,121.1+(i-7)*1.1));
        rejected(() -> verify(negativeBaseline,7,7));
        List<String> generation=good();generation.set(8,generation.get(8).replace("\"generation\":1","\"generation\":2"));
        rejected(() -> verify(generation,7,7));
        List<String> repeated=good();repeated.add(7,"\"kind\":\"resume\"");
        rejected(() -> verify(repeated,8,8));
        List<String> newRun=good();newRun.add("\"kind\":\"launch\"");
        rejected(() -> verify(newRun,7,7));
        List<String> lateError=good();lateError.add("\"kind\":\"cleanup-error\"");
        rejected(() -> verify(lateError,7,7));
        List<String> lateMove=good();lateMove.add(observation(14,150));lateMove.add(observation(15,151.1));
        rejected(() -> verify(lateMove,7,7)); // Later good samples cannot hide the first bad jump.
        List<String> noResume=good();noResume.set(6,"\"kind\":\"resize\"");
        rejected(() -> verify(noResume,7,7));
        List<String> noRelease=good();noRelease.set(4,"\"kind\":\"resize\"");
        rejected(() -> verify(noRelease,7,7));
        List<String> invalidPath=good();invalidPath.set(8,invalidPath.get(8).replace("}]}","},{\"unitId\":\"u1\"}]}"));
        rejected(() -> verify(invalidPath,7,7));
        List<String> duplicate=good();
        JsonValue duplicateRow=json("{"+duplicate.get(8)+"}");
        duplicateRow.get("sample").get("units").addChild(json("{unitId:u1}"));
        if (duplicateRow.get("sample").get("units").size!=2) throw new AssertionError("Bad duplicate fixture");
        String duplicateJson=duplicateRow.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json);
        duplicate.set(8,duplicateJson.substring(1,duplicateJson.length()-1));
        rejected(() -> verify(duplicate,7,7));
        List<String> still=good();
        for (int i=7;i<10;i++) still.set(i,observation(i+4,120));
        rejected(() -> verify(still,7,7));
        List<String> rounding=good();rounding.set(7,observation(11,Math.nextUp(121.1)));
        if (verify(rounding,7,7)!=3) throw new AssertionError("One-ULP arithmetic rounding rejected");
        List<String> outOfBounds=good();outOfBounds.set(7,observation(11,400));
        rejected(() -> verify(outOfBounds,7,7));
        byte[] malformed=bytes(rows);
        rejected(() -> SandboxPauseOracle.verify(java.util.Arrays.copyOf(malformed,malformed.length-1),1,json("{x:110,y:290}"),
            boundary(rows,7),boundary(rows,7),20,21,0));
        rejected(() -> SandboxPauseOracle.verify(bytes(rows),1,json("{x:110,y:290}"),
            boundary(rows,7),bytes(rows).length+1,20,21,0));
        if (verify(rows.subList(0,9),7,7)!=2) throw new AssertionError("Prefix readiness lost");
        System.out.println("PASS pause raw-byte boundaries, first resumed frame, continuity and late failures (constructed host records)");
    }
}
