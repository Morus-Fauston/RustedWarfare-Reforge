package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.util.ArrayList;
import java.util.List;

/** Constructed records validate the checker; no Android lifecycle is emulated. */
public final class SandboxRecreateOracleTest {
    private static JsonValue json(String text) { return new JsonReader().parse(text); }
    private static final JsonValue TARGET=json("{x:110,y:290}");
    private static void require(boolean value) { if(!value)throw new AssertionError("Recreate evidence check failed"); }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid recreation evidence accepted");
    }
    private static JsonValue sample(boolean active,long step,double time) {
        JsonValue s=json("{worldId:p002-knowledge:1,scenarioId:p002-knowledge,playerId:p1,stepIndex:"+step+",simulationTime:"+time+",units:["
            +"{unitId:u1,ownerPlayerId:p1,typeId:tank,heading:90,position:{x:110,y:"+(active?150:110)+"},speed:"+(active?1.1:0)
            +",angularVelocity:0,movementState:"+(active?"moving":"idle")+",moveTarget:"+(active?"{x:110,y:290}":"null")+",pathPoints:"+(active?"[{x:110,y:290}]":"[]")+"}],knowledge:[]}");
        for(int i=0;i<600;i++)s.get("knowledge").addChild(json(i==155||(active&&i==300)
            ?"{visible:true,gid:1,blocked:false}":"{visible:false,gid:null,blocked:null}"));
        return s;
    }
    private static JsonValue row(String kind) { return json("{kind:"+kind+",generation:1}"); }
    private static JsonValue obs(String phase,boolean active,long step,double time) {
        JsonValue r=row("observation");r.addChild("phase",new JsonValue(phase));r.addChild("sample",sample(active,step,time));return r;
    }
    private static List<JsonValue> numbered(List<JsonValue> rows) {
        for(int i=0;i<rows.size();i++){rows.get(i).remove("sequence");rows.get(i).addChild("sequence",new JsonValue((long)i));}return rows;
    }
    private static List<JsonValue> old() {
        List<JsonValue> r=new ArrayList<>(List.of(obs("initial",false,0,0),obs("step",true,100,101),
            row("presentation-released"),row("pause"),obs("before-close",true,100,101),row("closed"),row("match-exit"),
            row("application-disposed"),json("{kind:activity-destroyed,changingConfigurations:true}"),json("{kind:evidence-completed,status:normal}")));
        return numbered(r);
    }
    private static List<JsonValue> fresh() {
        return numbered(new ArrayList<>(List.of(json("{kind:launch,restoredActivity:true}"),row("resize"),
            json("{kind:load-request,generation:1,mode:MEMORY,shareAlliedVision:false}"),obs("initial",false,0,0),row("load-ready"),
            obs("step",false,0,1),obs("step",false,1,2),obs("step",false,2,3),
            obs("step",false,3,4),obs("step",false,4,5))));
    }
    private static List<JsonValue> encoded(List<JsonValue> rows) {
        for (JsonValue row : rows) if (row.has("sample")) {
            JsonValue sample = SandboxKnowledgeEncodingTest.encodedSample(row.get("sample"));
            row.remove("sample"); row.addChild("sample", sample);
        }
        return rows;
    }
    static void encodedKnowledgeRegression() {
        JsonValue birth = sample(false,0,0), active = sample(true,100,101);
        JsonValue encodedBirth = SandboxKnowledgeEncodingTest.encodedSample(birth), encodedActive = SandboxKnowledgeEncodingTest.encodedSample(active);
        require(SandboxRecreateOracle.learned(encodedBirth,encodedActive,TARGET)==300);
        require(SandboxRecreateOracle.learned(birth,encodedActive,TARGET)==300);
        require(SandboxRecreateOracle.closed(encoded(old()),1,2,true,TARGET));
        require(SandboxRecreateOracle.fresh(encoded(fresh()),birth,"MEMORY",2,7,300)==3);
        require(SandboxRecreateOracle.fresh(fresh(),encodedBirth,"MEMORY",2,7,300)==3);
        List<JsonValue> late = old(); late.add(row("cleanup-error")); numbered(late);
        rejected(()->SandboxRecreateOracle.closed(encoded(late),1,2,true,TARGET));
    }
    public static void main(String[] args) {
        require(!SandboxRecreateCleanup.resolved(true,1,false,true,true));
        java.util.concurrent.atomic.AtomicInteger observed=new java.util.concurrent.atomic.AtomicInteger(1);
        java.util.concurrent.atomic.AtomicInteger finished=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        List<Throwable> cleanupErrors=new ArrayList<>();
        SandboxRecreateCleanup.poll(()->finished.set(observed.get()),()->{throw new IllegalArgumentException("attach rejected");},
            ()->reads.incrementAndGet(),cleanupErrors::add);
        require(finished.get()==1&&reads.get()==1&&cleanupErrors.size()==1);
        observed.set(2); // Actual replacement notification arrives after the old host was already closed.
        SandboxRecreateCleanup.poll(()->finished.set(observed.get()),()->{},()->reads.incrementAndGet(),cleanupErrors::add);
        require(finished.get()==2&&SandboxRecreateCleanup.resolved(true,2,true,true,true));
        require(!SandboxRecreateCleanup.resolved(true,3,true,true,true));
        int previousReads=reads.get();java.util.concurrent.atomic.AtomicBoolean attachedAfterFinishFailure=new java.util.concurrent.atomic.AtomicBoolean();
        SandboxRecreateCleanup.poll(()->{throw new IllegalArgumentException("finish failed");},()->attachedAfterFinishFailure.set(true),
            ()->reads.incrementAndGet(),cleanupErrors::add);
        require(attachedAfterFinishFailure.get()&&reads.get()==previousReads+1&&cleanupErrors.size()==2);
        for(String mode:List.of("MEMORY","HARDCORE"))require(SandboxFunctionalOracle.parameters(
            java.util.Map.of("mode",mode,"shared","false","exercise","recreate")).exercise.equals("recreate"));
        rejected(()->SandboxFunctionalOracle.parameters(java.util.Map.of("mode","NO_FOG","shared","false","exercise","recreate")));
        rejected(()->SandboxFunctionalOracle.parameters(java.util.Map.of("mode","MEMORY","shared","true","exercise","recreate")));
        require(SandboxRecreateOracle.learned(sample(false,0,0),sample(true,100,101),TARGET)==300);
        require(SandboxRecreateOracle.learned(sample(false,0,0),sample(false,1,2),TARGET)<0);
        require(SandboxRecreateOracle.closed(old(),1,2,true,TARGET));
        List<JsonValue> partial=old();partial.remove(partial.size()-1);
        require(!SandboxRecreateOracle.closed(partial,1,2,true,TARGET));
        List<JsonValue> wrongFlag=old();wrongFlag.get(8).get("changingConfigurations").set(false);
        rejected(()->SandboxRecreateOracle.closed(wrongFlag,1,2,true,TARGET));
        List<JsonValue> ended=old();ended.get(4).get("sample").get("units").get(0).get("moveTarget").set((String)null);
        rejected(()->SandboxRecreateOracle.closed(ended,1,2,true,TARGET));
        require(SandboxRecreateOracle.released(ended,1,2,true)); // Resource closure does not erase the failed active precondition.
        List<JsonValue> late=old();late.add(row("cleanup-error"));numbered(late);
        rejected(()->SandboxRecreateOracle.closed(late,1,2,true,TARGET));
        rejected(()->SandboxRecreateOracle.closed(old(),1,4,true,TARGET));
        List<JsonValue> stale=old();
        stale.add(1,obs("step",true,99,100));numbered(stale);
        JsonValue forgotten=stale.get(2).get("sample").get("knowledge").get(300);
        forgotten.get("visible").set(false);forgotten.get("gid").set((String)null);forgotten.get("blocked").set((String)null);
        rejected(()->SandboxRecreateOracle.closed(stale,1,2,true,TARGET));
        require(SandboxRecreateOracle.setup(fresh().subList(0,2)));
        rejected(()->SandboxRecreateOracle.setup(fresh().subList(0,3)));
        List<JsonValue> automatic=fresh();automatic.add(2,obs("initial",true,100,101));numbered(automatic);
        rejected(()->SandboxRecreateOracle.fresh(automatic,sample(false,0,0),"MEMORY",3,5,300));
        require(SandboxRecreateOracle.fresh(fresh(),sample(false,0,0),"MEMORY",2,7,300)==3);
        for(String state:List.of("moving","decelerating")){
            List<JsonValue> residual=fresh();residual.get(3).get("sample").get("units").get(0).get("movementState").set(state);
            rejected(()->SandboxRecreateOracle.fresh(residual,sample(false,0,0),"MEMORY",2,7,300));
        }
        List<JsonValue> badState=fresh();badState.get(7).get("sample").get("units").get(0).get("movementState").set(true);
        rejected(()->SandboxRecreateOracle.fresh(badState,sample(false,0,0),"MEMORY",2,7,300));
        List<JsonValue> missingState=fresh();missingState.get(7).get("sample").get("units").get(0).remove("movementState");
        rejected(()->SandboxRecreateOracle.fresh(missingState,sample(false,0,0),"MEMORY",2,7,300));
        List<JsonValue> retained=fresh();retained.get(3).get("sample").get("knowledge").get(300).get("gid").set(1L,null);
        rejected(()->SandboxRecreateOracle.fresh(retained,sample(false,0,0),"MEMORY",2,7,300));
        List<JsonValue> lateMotion=fresh();lateMotion.set(7,obs("step",true,2,3));numbered(lateMotion);
        rejected(()->SandboxRecreateOracle.fresh(lateMotion,sample(false,0,0),"MEMORY",2,7,300));
        List<JsonValue> selected=fresh();selected.add(row("selection"));numbered(selected);
        rejected(()->SandboxRecreateOracle.fresh(selected,sample(false,0,0),"MEMORY",2,7,300));
        List<JsonValue> duplicate=fresh();duplicate.get(6).get("sample").get("units").addChild(json("{unitId:u1}"));
        rejected(()->SandboxRecreateOracle.fresh(duplicate,sample(false,0,0),"MEMORY",2,7,300));
        List<JsonValue> typed=fresh();typed.get(6).get("sample").get("knowledge").get(155).get("visible").set("true");
        rejected(()->SandboxRecreateOracle.fresh(typed,sample(false,0,0),"MEMORY",2,7,300));
        require(SandboxRecreateOracle.fresh(fresh(),sample(false,0,0),"MEMORY",2,9,300)==1);
        List<JsonValue> full=fresh();long finish=full.size();
        full.addAll(List.of(row("presentation-released"),row("pause"),obs("before-close",false,4,5),row("closed"),row("match-exit"),
            row("application-disposed"),json("{kind:activity-destroyed,changingConfigurations:false}"),json("{kind:evidence-completed,status:normal}")));
        numbered(full);
        require(SandboxRecreateOracle.closed(full,1,finish,false,null));
        require(SandboxRecreateOracle.fresh(full,sample(false,0,0),"MEMORY",2,7,300)==3);
        full.get(12).get("sample").get("units").get(0).get("speed").set(1L,null);
        rejected(()->SandboxRecreateOracle.fresh(full,sample(false,0,0),"MEMORY",2,7,300));
        SandboxRecreateOracle.combined(5L*1024*1024,5L*1024*1024,200,200,false);
        rejected(()->SandboxRecreateOracle.combined(7L*1024*1024,7L*1024*1024,200,200,false));
        rejected(()->SandboxRecreateOracle.combined(1,1,300,300,true));
        rejected(()->SandboxRecreateOracle.combined(Long.MAX_VALUE,1,1,1,true));
        rejected(()->SandboxRecreateOracle.combined(1,1,-1,1,true));
        System.out.println("PASS two-run setup/close, active knowledge, clean full new stream and aggregate budgets (host records only)");
    }
}
