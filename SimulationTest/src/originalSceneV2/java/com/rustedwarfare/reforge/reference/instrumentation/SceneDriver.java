package com.rustedwarfare.reforge.reference.instrumentation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Approved scene normal-thread segments; original observer and S06 driver stay unchanged. */
final class SceneDriver {
    interface Sink { void phase(Map<String,Object> record) throws Throwable; }
    private final long[] durations;
    private final ScenePlan plan;
    private Object lastCommand;
    private Map<String,Object> sceneInitial;
    private final ClassLoader loader;
    private final SceneAccess original;
    private final Object engine,unit,player,definition,controller;
    private final String mapPath,runId,mode;
    private final String expectedEj;
    private final Class<?> k,p,bp,e,en,eo,ak,c,observer;
    private final Field queue;
    private Thread actualThread;
    private final long endNanos;
    private final List<Object> allErrors=new ArrayList<Object>();
    private String expectedStableJson;
    private double completedQ;
    private boolean scenarioEnded;
    private Map<String,Object> finalFacts=new LinkedHashMap<String,Object>();

    SceneDriver(ClassLoader loader, SceneAccess original, Object engine, Object unit, Object player,
            Object definition, String mapPath, String runId, String mode, ScenePlan plan) throws Exception {
        InitialProtocol.require(mode.equals("on") || mode.equals("off") || mode.equals("bare") || mode.equals("fault"),"motion-mode");
        this.loader=loader; this.original=original; this.engine=engine; this.unit=unit; this.player=player;
        this.definition=definition; this.mapPath=mapPath; this.runId=runId; this.mode=mode;
        endNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        k=type("com.corrodinggames.rts.gameFramework.k"); p=type("com.corrodinggames.rts.game.p");
        bp=type("com.corrodinggames.rts.game.units.bp"); e=type("com.corrodinggames.rts.gameFramework.e");
        en=type("com.corrodinggames.rts.game.units.en"); eo=type("com.corrodinggames.rts.game.units.eo");
        ak=type("com.corrodinggames.rts.gameFramework.ak"); c=type("com.corrodinggames.rts.gameFramework.c");
        controller=field(k,"cc",c).get(engine); queue=field(c,"b",ArrayList.class);
        expectedEj=Long.toString(field(type("com.corrodinggames.rts.gameFramework.ah"),"ej",long.class).getLong(unit));
        InitialProtocol.require(!expectedEj.equals("0"),"motion-ej-zero");
        observer=mode.equals("bare")?null:type("com.rustedwarfare.reforge.reference.motion.NativeMotionTrace");
        this.plan=plan;durations=plan.durations();initializePose();
    }
    private void initializePose() throws Exception {
        synchronized(engine){
            stopped();sceneInitial=new LinkedHashMap<String,Object>();
            sceneInitial.put("caseId",plan.id);sceneInitial.put("origin","approved-pose-after-factory-global-registration-before-first-simulation-and-spatial-player-registration");
            sceneInitial.put("map",SceneInputs.verify(mapPath,plan));
            sceneInitial.put("before",original.snapshot(mapPath,definition).raw);
            Class<?> ah=type("com.corrodinggames.rts.gameFramework.ah"),ce=type("com.corrodinggames.rts.game.units.ce");
            InitialProtocol.require(Float.floatToRawIntBits(field(ah,"eq",float.class).getFloat(unit))==Float.floatToRawIntBits(plan.x)
                && Float.floatToRawIntBits(field(ah,"er",float.class).getFloat(unit))==Float.floatToRawIntBits(plan.y)
                && Float.floatToRawIntBits(field(ce,"ci",float.class).getFloat(unit))==Float.floatToRawIntBits(plan.ci),"scene-pose-readback");
            sceneInitial.put("after",original.snapshot(mapPath,definition).raw);
        }
    }
    Map<String,Object> sceneInitial(){return new LinkedHashMap<String,Object>(sceneInitial);}
    private Class<?> type(String name) throws Exception {
        Class<?> value=Class.forName(name,false,loader);
        InitialProtocol.require(value.getClassLoader()==loader && Modifier.isPublic(value.getModifiers()),"motion-public-owner:"+name); return value;
    }
    private Field field(Class<?> owner,String name,Class<?> value) throws Exception {
        Field f=owner.getField(name); InitialProtocol.require(f.getType()==value && Modifier.isPublic(f.getModifiers())
            && !Modifier.isStatic(f.getModifiers()),"motion-field:"+owner.getName()+"."+name); return f;
    }
    private Object invoke(Class<?> owner,String name,Class<?> result,Object target,Class<?>[] parameters,Object... args) throws Exception {
        Method m=owner.getMethod(name,parameters);InitialProtocol.require(m.getReturnType()==result && Modifier.isPublic(m.getModifiers())
            && Modifier.isStatic(m.getModifiers())==(target==null),"motion-method:"+owner.getName()+"."+name);return m.invoke(target,args);
    }
    private Object engineCall(String name) throws Exception { return invoke(k,name,void.class,engine,new Class<?>[0]); }
    private List<?> commands() throws Exception { return (List<?>)queue.get(controller); }
    private List<Thread> threads() {
        List<Thread> found=new ArrayList<Thread>();
        for(Thread t:Thread.getAllStackTraces().keySet())
            if(t.isAlive() && t.getClass()==ak && t.getClass().getClassLoader()==loader)found.add(t);
        return found;
    }
    private void stopped() throws Exception {
        InitialProtocol.require(!original.running(engine) && (actualThread==null || !actualThread.isAlive()) && threads().isEmpty(),"motion-original-not-stopped");
    }
    private SceneAccess.Snapshot stable(Map<String,Object> record,String side) throws Throwable {
        SceneAccess.Snapshot s=original.snapshot(mapPath,definition);record.put(side,s.raw);s.requireReadable();
        InitialProtocol.require(s.engine==engine && s.player==player && s.units!=null && s.units.length==1 && s.units[0]==unit
            && s.pathMatches && s.dimensionsMatch && s.playerMatches,"motion-stable-world");
        Object rawUnits=s.raw.get("units");InitialProtocol.require(rawUnits instanceof List && ((List<?>)rawUnits).size()==1,"motion-stable-unit-row");
        Object rawUnit=((List<?>)rawUnits).get(0);InitialProtocol.require(rawUnit instanceof Map,"motion-stable-unit-fields");
        Map<?,?> facts=(Map<?,?>)rawUnit;
        InitialProtocol.require(Boolean.TRUE.equals(facts.get("ownerIsBp")) && Boolean.TRUE.equals(facts.get("qIsFactoryDefinition"))
            && expectedEj.equals(facts.get("ej")),"motion-stable-unit-identity");return s;
    }
    private Object command(int phase,Map<String,Object> raw) throws Exception {
        InitialProtocol.require(commands().isEmpty(),"motion-command-queue-preexisting");
        if(plan.stationary){raw.put("action","observe");raw.put("inputSequence",-1);return new Object();}
        if(phase>0 && (!plan.stop || phase>1)){raw.put("action","continue");raw.put("inputSequence",plan.stop?1:0);return lastCommand;}
        Object command=invoke(c,"a",e,controller,new Class<?>[]{p},player);
        raw.put("createdNanos",Long.toString(System.nanoTime()));
        InitialProtocol.require(command!=null && command.getClass()==e,"motion-command-type");
        // The ce overload creates an attack target. Selection MUST use bp.
        invoke(e,"a",void.class,command,new Class<?>[]{bp},unit);
        boolean stop=plan.stop && phase==1;float x=plan.targetX,y=plan.targetY;
        raw.put("action",stop?"stop":"move");raw.put("inputSequence",stop?1:0);
        if(stop)field(e,"p",boolean.class).setBoolean(command,true);
        else invoke(e,"a",void.class,command,new Class<?>[]{float.class,float.class},x,y);
        int selected=(Integer)invoke(e,"c",int.class,command,new Class<?>[0]);
        raw.put("selectedCount",selected);raw.put("ownerSame",field(e,"i",p).get(command)==player);
        raw.put("d",field(e,"d",int.class).getInt(command));raw.put("c",field(e,"c",int.class).getInt(command));
        raw.put("p",field(e,"p",boolean.class).getBoolean(command));raw.put("queueSize",commands().size());
        raw.put("queueContainsSameOnly",commands().size()==1 && commands().get(0)==command);
        Object waypoint=field(e,"j",en).get(command);raw.put("waypointPresent",waypoint!=null);
        if(waypoint!=null){
            Object kind=field(en,"a",eo).get(waypoint);Field move=eo.getField("a");
            InitialProtocol.require(move.getType()==eo && Modifier.isPublic(move.getModifiers()) && Modifier.isStatic(move.getModifiers()),"motion-move-enum");
            boolean same=kind==move.get(null);raw.put("waypointIsMove",same);raw.put("waypointEnumName",((Enum<?>)kind).name());
            float observedX=field(en,"e",float.class).getFloat(waypoint),observedY=field(en,"f",float.class).getFloat(waypoint);
            raw.put("x",SceneAccess.floatValue(observedX));raw.put("y",SceneAccess.floatValue(observedY));
            InitialProtocol.require(!stop && same && Float.floatToRawIntBits(observedX)==Float.floatToRawIntBits(x)
                && Float.floatToRawIntBits(observedY)==Float.floatToRawIntBits(y),"motion-move-command-readback");
        }
        InitialProtocol.require(selected==1 && Boolean.TRUE.equals(raw.get("ownerSame")) && Boolean.TRUE.equals(raw.get("queueContainsSameOnly"))
            && field(e,"p",boolean.class).getBoolean(command)==stop && (stop?waypoint==null:waypoint!=null),"motion-command-readback");
        raw.put("configuredNanos",Long.toString(System.nanoTime()));lastCommand=command;return command;
    }
    void run(Sink sink) throws Throwable {
        Throwable first=null;
        try{
            synchronized(engine){
                stopped();
                if(observer!=null)invoke(observer,"configure",void.class,null,
                    new Class<?>[]{String.class,Object.class,Object.class,Object.class,Object.class,boolean.class,int.class},
                    runId,engine,unit,player,definition,!mode.equals("off"),mode.equals("fault")?2:0);
            }
            for(int phase=0;phase<durations.length && !scenarioEnded;phase++)runPhase(phase,sink);
        }catch(Throwable error){first=retain(first,error,-1,"motion-run",null);}
        finally{
            try{checkFinalStable();}catch(Throwable error){first=retain(first,error,-1,"final-stability",null);
                try{synchronized(engine){engineCall("B");stopped();}}catch(Throwable stopError){first=retain(first,stopError,-1,"final-stop-after-failure",null);}}
            if(System.nanoTime()>=endNanos)first=retain(first,new IllegalStateException("motion-stage-timeout-at-end"),-1,"motion-end-budget",null);
        }
        if(first!=null)throw first;
    }
    List<Object> errors(){return new ArrayList<Object>(allErrors);}
    Map<String,Object> finalFacts(){return new LinkedHashMap<String,Object>(finalFacts);}
    void checkFinalStable() throws Throwable {
        finalFacts=new LinkedHashMap<String,Object>();finalFacts.put("sceneInitial",sceneInitial());
        finalFacts.put("mapAfter",SceneInputs.verify(mapPath,plan));
        synchronized(engine){
            stopped();SceneAccess.Snapshot s=stable(finalFacts,"snapshot");
            InitialProtocol.require(expectedStableJson==null || expectedStableJson.equals(InitialProtocol.json(s.raw)),"motion-world-changed-after-stop");
            if(observer!=null){
                @SuppressWarnings("unchecked") Map<String,Object> status=(Map<String,Object>)invoke(observer,"finish",Map.class,null,new Class<?>[0]);
                finalFacts.put("observer",status);InitialProtocol.require(status.get("firstError")==null && Boolean.TRUE.equals(status.get("sealed")),"motion-final-observer-error");
            }
        }
    }
    private Throwable retain(Throwable first,Throwable error,int phase,String stage,List<Object> local){
        Map<String,Object> raw=new LinkedHashMap<String,Object>();raw.put("phase",phase);raw.put("stage",stage);
        List<Object> causes=new ArrayList<Object>();raw.put("causes",causes);
        java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        Throwable cause=error;for(int depth=0;cause!=null && depth<8 && seen.add(cause);depth++){
            Map<String,Object> r=new LinkedHashMap<String,Object>();r.put("type",cause.getClass().getName());r.put("message",cause.getMessage());causes.add(r);
            cause=cause instanceof java.lang.reflect.InvocationTargetException?((java.lang.reflect.InvocationTargetException)cause).getTargetException():cause.getCause();
        }
        allErrors.add(raw);if(local!=null)local.add(raw);
        if(first==null)return error;if(first!=error)first.addSuppressed(error);return first;
    }
    private void runPhase(int phase,Sink sink) throws Throwable {
        Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("phase",phase);row.put("mode",mode);
        row.put("wallLimitMillis",durations[phase]);row.put("minimumPhaseQ",plan.minimumPhaseQ(phase));
        row.put("beginNanos",Long.toString(System.nanoTime()));
        List<Object> errors=new ArrayList<Object>();row.put("errors",errors);
        Throwable first=null;boolean published=false;int beforeBu=0;boolean haveBefore=false;
        try{
            SceneClock clock=null;
            synchronized(engine){
                // A lifecycle restart in the pause is a failure, never silently absorbed by B.
                stopped();
                engineCall("B");stopped();
                SceneAccess.Snapshot before=stable(row,"before"); beforeBu=(Integer)before.raw.get("bu");haveBefore=true;
                InitialProtocol.require(expectedStableJson==null || expectedStableJson.equals(InitialProtocol.json(before.raw)),"motion-world-changed-between-phases");
                Map<String,Object> commandRaw=new LinkedHashMap<String,Object>();row.put("command",commandRaw);
                Object command=command(phase,commandRaw);
                if(observer!=null){invoke(observer,"beginPhase",void.class,null,new Class<?>[]{int.class,Object.class},phase,command);published=true;}
                InitialProtocol.require(System.nanoTime()<endNanos,"motion-stage-timeout-before-start");
                row.put("startRequestedNanos",Long.toString(System.nanoTime()));engineCall("A");
                List<Thread> alive=threads();InitialProtocol.require(alive.size()==1,"motion-start-unique-thread");actualThread=alive.get(0);
                row.put("threadId",Long.toString(actualThread.getId()));row.put("threadClass",actualThread.getClass().getName());
                row.put("startReturnedNanos",Long.toString(System.nanoTime()));
            }
            if(plan.minimumPhaseQ(phase)>=0)clock=new SceneClock(observer,loader);
            long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(durations[phase]);
            for(;;){
                long now=System.nanoTime();InitialProtocol.require(now<endNanos,"motion-stage-timeout");
                InitialProtocol.require(actualThread.isAlive(),"motion-original-thread-died-early");
                if(observer!=null){
                    Throwable error=(Throwable)invoke(observer,"failure",Throwable.class,null,new Class<?>[0]);if(error!=null)throw error;
                    Thread bound=(Thread)invoke(observer,"boundThread",Thread.class,null,new Class<?>[0]);
                    InitialProtocol.require(bound==null || bound==actualThread,"motion-callback-thread-mismatch");
                }
                double phaseQ=clock.poll();
                boolean terminal=!plan.stationary && !(plan.stop && phase==0) && clock.terminalTail(plan);
                boolean threshold=(plan.stationary || plan.stop && phase==0?phaseQ:completedQ+phaseQ)>=plan.minimumPhaseQ(phase);
                if(terminal || threshold){
                    row.put("qThresholdObserved",clock.facts());row.put("qThresholdObservedNanos",Long.toString(System.nanoTime()));
                    row.put("endReason",terminal?"terminal-plus-18q":plan.stationary?"stationary-18q":plan.stop && phase==0?"stop-requested-at-30q":phase==durations.length-1?"600q-budget":"predeclared-segment-q");
                    scenarioEnded=terminal || plan.stationary || phase==durations.length-1;
                    break;
                }
                if(now>=until){InitialProtocol.require(clock==null,"scene-q-threshold-wall-timeout");break;}
                Thread.sleep(5);
            }
        }catch(Throwable error){first=retain(first,error,phase,"phase-operation",errors);}
        finally{
            row.put("stopRequestedNanos",Long.toString(System.nanoTime()));
            synchronized(engine){
                boolean proven=false;
                try{
                    engineCall("B");row.put("stopReturnedNanos",Long.toString(System.nanoTime()));
                }catch(Throwable error){first=retain(first,error,phase,"normal-stop",errors);}
                try{
                    stopped();proven=true;
                    row.put("threadJoined",actualThread!=null && !actualThread.isAlive());
                }catch(Throwable error){first=retain(first,error,phase,"stop-confirmation",errors);}
                if(proven){
                    if(published)try{
                        @SuppressWarnings("unchecked") Map<String,Object> trace=(Map<String,Object>)invoke(observer,"seal",Map.class,null,new Class<?>[]{Thread.class},actualThread);
                        row.put("trace",trace);
                        InitialProtocol.require(trace.get("firstError")==null,"motion-observer:"+trace.get("firstError"));
                        long[][] raw=(long[][])trace.get("rows");double sealedQ=0;
                        String[] fields=(String[])trace.get("fields");
                        int iIndex=java.util.Arrays.asList(fields).indexOf("effectiveIBits");
                        InitialProtocol.require(iIndex>=0,"scene-missing-effective-I");
                        int width=3+fields.length;
                        for(long[] sample:raw){InitialProtocol.require(sample.length==2*width,"scene-row-width");sealedQ+=Float.intBitsToFloat((int)sample[width+3+iIndex]);}
                        completedQ+=sealedQ;row.put("sealedCumulativeQ",completedQ);
                    }catch(Throwable error){first=retain(first,error,phase,"observer-seal",errors);}
                    try{
                        SceneAccess.Snapshot after=stable(row,"after");expectedStableJson=InitialProtocol.json(after.raw);
                        InitialProtocol.require(haveBefore && (Integer)after.raw.get("bu")>beforeBu,"motion-zero-native-progress");
                        int remainingCommands=commands().size();row.put("queueSizeAfterStop",remainingCommands);
                        InitialProtocol.require(remainingCommands==0,"motion-command-not-consumed");
                    }catch(Throwable error){first=retain(first,error,phase,"stopped-after-readback",errors);}
                }else{row.put("traceUnavailableReason","normal-stop-not-proven");row.put("afterUnavailableReason","normal-stop-not-proven-no-live-world-read");}
            }
            if(System.nanoTime()>=endNanos)first=retain(first,new IllegalStateException("motion-stage-timeout-after-cleanup"),phase,"after-cleanup-budget",errors);
            row.put("firstError",first==null?null:first.toString());row.put("completed",first==null);
            try{sink.phase(row);}catch(Throwable error){first=retain(first,error,phase,"phase-export",errors);}
            if(System.nanoTime()>=endNanos)first=retain(first,new IllegalStateException("motion-stage-timeout-after-export"),phase,"after-export-budget",errors);
        }
        if(first!=null)throw first;
    }
}
