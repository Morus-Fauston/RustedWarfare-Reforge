package com.rustedwarfare.reforge.android;
import java.util.Map;
public final class SandboxFunctionalOracleTest {
    private static com.badlogic.gdx.utils.JsonValue json(String raw) {
        return new com.badlogic.gdx.utils.JsonReader().parse(raw);
    }
    private static void reject(Runnable body) {
        try { body.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid pre-start parameters were accepted");
    }
    public static void main(String[] args) {
        reject(() -> SandboxFunctionalOracle.parameters(Map.of("mode","MEMORY","shared","yes","exercise","controls")));
        reject(() -> SandboxFunctionalOracle.parameters(Map.of("mode","MEMORY","shared","false")));
        reject(() -> SandboxFunctionalOracle.parameters(Map.of("mode","MEMORY","shared","false","exercise","controls","path","/other")));
        for (String mode : new String[]{"MEMORY","HARDCORE"}) {
            var c=SandboxFunctionalOracle.parameters(Map.of("mode",mode,"shared","false","exercise","visibility"));
            if (!c.exercise.equals("visibility") || c.shared) throw new AssertionError("Visibility config changed");
            reject(() -> SandboxFunctionalOracle.parameters(Map.of("mode",mode,"shared","true","exercise","visibility")));
        }
        reject(() -> SandboxFunctionalOracle.parameters(Map.of("mode","NO_FOG","shared","false","exercise","visibility")));
        for (String mode : new String[]{"NO_FOG","MEMORY","HARDCORE"})
            for (String shared : new String[]{"false","true"})
                for (String exercise : new String[]{"controls","explore"}) {
                    var actual = SandboxFunctionalOracle.parameters(Map.of("mode",mode,"shared",shared,"exercise",exercise));
                    if (!actual.mode.equals(mode) || actual.shared != shared.equals("true") || !actual.exercise.equals(exercise))
                        throw new AssertionError("Finite configuration changed");
                }
        System.out.println("PASS finite pre-start configuration");
        var stream = new SandboxFunctionalOracle.Stream();
        stream.feed("{\"kind\":\"observation\",\"phase\":\"st".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, false);
        if (!stream.records.isEmpty()) throw new AssertionError("Partial line consumed");
        stream.feed("ep\",\"generation\":1}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), 1, false);
        if (stream.steps != 1 || stream.records.size() != 1) throw new AssertionError("Incremental complete line lost");
        reject(() -> new SandboxFunctionalOracle.Stream().feed("{\n".getBytes(), 0, false));
        var partial = new SandboxFunctionalOracle.Stream(); partial.feed("{".getBytes(),0,false);
        reject(partial::complete);
        var generations = new SandboxFunctionalOracle.Stream();
        String step = "{\"kind\":\"observation\",\"phase\":\"step\",\"generation\":1}\n";
        generations.feed(step.repeat(250).getBytes(),0,false);
        generations.feed(step.replace("generation\":1","generation\":2").repeat(250).getBytes(),0,false);
        generations.feed(step.replace("generation\":1","generation\":3").getBytes(),0,true);
        reject(generations::complete);
        reject(() -> new SandboxFunctionalOracle.Stream().feed(new byte[12*1024*1024+1],0,false));
        reject(() -> new SandboxFunctionalOracle.Stream().feed(new byte[16*1024*1024+1],0,true));
        reject(() -> new SandboxFunctionalOracle.Stream().feed(new byte[0],15001,false));
        new SandboxFunctionalOracle.Stream().feed(new byte[0],2000,true);
        reject(() -> new SandboxFunctionalOracle.Stream().feed(new byte[0],3001,true));
        System.out.println("PASS partial/bad lines, cumulative generation steps, byte/time boundaries");
        reject(() -> SandboxFunctionalOracle.configuration(json("{mode:MEMORY,shareAlliedVision:false}"), "HARDCORE",false));
        reject(() -> SandboxFunctionalOracle.accepted(json("{type:MoveAction,result:{accepted:false}}"),"MoveAction"));
        var still = json("{position:{x:110,y:110},speed:0,moveTarget:null,pathPoints:[]}");
        if (SandboxFunctionalOracle.moved(still,110,110)) throw new AssertionError("No movement falsely accepted");
        var walking = json("{position:{x:110,y:120},speed:1,moveTarget:{x:110,y:150},pathPoints:[{x:110,y:150}]}");
        if (SandboxFunctionalOracle.stopped(walking)) throw new AssertionError("Walking falsely accepted as stopped");
        var errors = new SandboxFunctionalOracle.Failures();
        var first = new IllegalArgumentException("first"); var cleanup = new IllegalArgumentException("cleanup");
        errors.business(first); errors.cleanup(cleanup);
        if (errors.primary != first || errors.secondary.size()!=1 || errors.secondary.get(0)!=cleanup)
            throw new AssertionError("Cleanup replaced primary");
        reject(() -> SandboxFunctionalOracle.initial(json("{scenarioId:p002-knowledge,playerId:p1,stepIndex:0,simulationTime:0,units:[]}"),"MEMORY",true));
        SandboxFunctionalOracle.configuration(json("{mode:MEMORY,shareAlliedVision:false}"),"MEMORY",false);
        SandboxFunctionalOracle.accepted(json("{type:MoveAction,unitId:u1,actorPlayerId:p1,result:{accepted:true}}"),"MoveAction");
        if (!SandboxFunctionalOracle.stopped(still) || !SandboxFunctionalOracle.moved(walking,110,110))
            throw new AssertionError("Real movement/stop positive controls rejected");
        String u1="{unitId:u1,ownerPlayerId:p1,position:{x:110,y:110},speed:0,angularVelocity:0,moveTarget:null,pathPoints:[]}";
        String u2="{unitId:u2,ownerPlayerId:p2,position:{x:510,y:310},speed:0,angularVelocity:0,moveTarget:null,pathPoints:[]}";
        String u3="{unitId:u3,ownerPlayerId:p3,position:{x:550,y:310},speed:0,angularVelocity:0,moveTarget:null,pathPoints:[]}";
        SandboxFunctionalOracle.initial(json("{scenarioId:p002-knowledge,playerId:p1,stepIndex:0,simulationTime:0,units:["+u1+"]}"),"MEMORY",false);
        SandboxFunctionalOracle.initial(json("{scenarioId:p002-knowledge,playerId:p1,stepIndex:0,simulationTime:0,units:["+u1+","+u2+","+u3+"]}"),"HARDCORE",true);
        SandboxFunctionalOracle.initial(json("{scenarioId:p002-knowledge,playerId:p1,stepIndex:0,simulationTime:0,units:["+u1+","+u2+","+u3+"]}"),"NO_FOG",false);
        System.out.println("PASS independent config/action/initial/motion and primary preservation");
        for (String kind : new String[]{"cleanup-error","load-error","launch-failed"}) {
            var closing = new SandboxFunctionalOracle.Stream();
            String chain = "{\"kind\":\"application-disposed\"}\n{\"kind\":\""+kind+"\",\"message\":\"late failure\"}\n"+
                "{\"kind\":\"activity-destroyed\"}\n{\"kind\":\"evidence-completed\",\"status\":\"normal\"}\n";
            closing.feed(chain.getBytes(),0,true);
            var lateErrors = new SandboxFunctionalOracle.Failures();
            closing.applicationErrors(lateErrors);
            if (lateErrors.primary == null) throw new AssertionError("Normal release chain masked late "+kind);
            if (closing.records.size()!=4) throw new AssertionError("Closing observation stopped after app error");
            closing.applicationErrors(lateErrors);
            if (!lateErrors.secondary.isEmpty()) throw new AssertionError("Same error reported twice");
            var previous = new SandboxFunctionalOracle.Failures();
            previous.business(first);
            var another = new SandboxFunctionalOracle.Stream(); another.feed(chain.getBytes(),0,true); another.applicationErrors(previous);
            if (previous.primary != first || previous.secondary.size()!=1) throw new AssertionError("Late app error replaced first failure");
        }
        System.out.println("PASS late application errors fail despite normal release and preserve first error");
        reject(() -> new SandboxFunctionalOracle.Stream().feed("{kind:observation,phase:step}\n".getBytes(),0,false));
        reject(() -> new SandboxFunctionalOracle.Stream().feed("{\"kind\":observation}\n".getBytes(),0,false));
        reject(() -> new SandboxFunctionalOracle.Stream().feed("{\"kind\":\"observation\",}\n".getBytes(),0,false));
        reject(() -> new SandboxFunctionalOracle.Stream().feed("{\"kind\":\"observation\"/*comment*/}\n".getBytes(),0,false));
        String invalidHex = "{\"kind\":\"observation\",\"text\":\"" + "\\" + "u００４１\"}\n";
        reject(() -> new SandboxFunctionalOracle.Stream().feed(invalidHex.getBytes(java.nio.charset.StandardCharsets.UTF_8),0,false));
        var positiveJson = new SandboxFunctionalOracle.Stream();
        String strictPositive = "{\"kind\":\"observation\",\"nested\":[true,false,null,{\"number\":-1.25e+2}],\"text\":\"" + "\\" + "u0041\\n\\\"\"}\n";
        positiveJson.feed(strictPositive.getBytes(java.nio.charset.StandardCharsets.UTF_8),0,false);
        if (positiveJson.records.size()!=1) throw new AssertionError("Strict JSON positive control rejected");
        System.out.println("PASS strict complete JSONL syntax including ASCII hex and valid escapes/nesting/numbers");
    }
}
