package com.rustedwarfare.reforge.reference.motion;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Target-side fixed experiment observer. Original classes are only public reflection targets. */
public final class NativeMotionTrace {
    private static volatile StepTrace active;
    private static volatile Throwable sessionFirst;
    private static NativeView view;
    private static Object engine;
    private static boolean enabled, configured;
    private static int phase=-1, faultAfter;
    private static Class<?> threadType;
    private static final Object[] EMPTY=new Object[0];
    private static final String[] BASE_FIELDS={"bu","bv","effectiveIBits","GBits","bqBits","ej","eqBits","erBits","ciBits",
        "tableSize","soleSame","ownerSame","definitionSame","queueSize","queuedCommandSame","foreignCommands",
        "waypointPresent","waypointIsMove","waypointEnumOrdinal","waypointXBits","waypointYBits","waypointCountO"};

    private static final String[] FIELDS=fields();
    private static String[] fields() {
        String[] result=new String[BASE_FIELDS.length+MinimalMotionObservation.FIELDS.length];
        System.arraycopy(BASE_FIELDS,0,result,0,BASE_FIELDS.length);
        System.arraycopy(MinimalMotionObservation.FIELDS,0,result,BASE_FIELDS.length,MinimalMotionObservation.FIELDS.length);
        return result;
    }
    private NativeMotionTrace() { }
    public static synchronized void configure(String runId, Object actualEngine, Object unit, Object owner, Object definition,
            boolean collect, int injectedFaultAfter) throws Exception {
        require(!configured && runId != null && java.util.UUID.fromString(runId).toString().equals(runId), "motion-configure");
        ClassLoader loader=NativeMotionTrace.class.getClassLoader();
        Class<?> batch=load(loader,"com.rustedwarfare.reforge.reference.boot.BatchSettings");
        require(("rwr_reference_"+runId.replace("-", "")).equals(method(batch,"name",String.class).invoke(null)),"motion-batch");
        method(load(loader,"com.rustedwarfare.reforge.reference.boot.GbootBootstrap"),"requireBackendPublished",void.class).invoke(null);
        require(actualEngine != null && Thread.holdsLock(actualEngine),"motion-configure-monitor");
        view=new NativeView(loader,actualEngine,unit,owner,definition);
        MinimalMotionObservation.configure(actualEngine,unit,collect);
        threadType=load(loader,"com.corrodinggames.rts.gameFramework.ak");
        require(Thread.class.isAssignableFrom(threadType),"motion-ak-type");
        engine=actualEngine; enabled=collect; faultAfter=injectedFaultAfter; configured=true;
    }
    public static synchronized void beginPhase(int index, Object command) {
        require(configured && sessionFirst == null && (active == null || active.failure() == null)
            && Thread.holdsLock(engine) && index == phase+1 && index < 4
            && (active == null || view.sealed),"motion-phase-order");
        require(command != null,"motion-command-null");
        MinimalMotionObservation.beginPhase();
        view.command=command; view.sealed=false;
        StepTrace next=new StepTrace(engine,threadType,view,enabled,512,FIELDS.length,index == 0 ? faultAfter : 0);
        next.begin(); phase=index; active=next;
    }
    public static void before(Object actualEngine, float delta) {
        StepTrace trace=active;if(trace!=null){MinimalMotionObservation.beginCall(actualEngine);trace.before(actualEngine,delta);if(trace.failure()!=null)latch(trace.failure());}
    }
    public static void after(Object actualEngine, float delta) {
        StepTrace trace=active;if(trace!=null){trace.after(actualEngine,delta);MinimalMotionObservation.endCall();if(trace.failure()!=null)latch(trace.failure());}
    }
    private static synchronized void latch(Throwable error) { if(sessionFirst==null)sessionFirst=error; }
    public static Throwable failure() { return sessionFirst!=null?sessionFirst:MinimalMotionObservation.failure(); }
    public static Thread boundThread() { StepTrace trace=active; return trace == null ? null : trace.boundThread(); }
    public static synchronized Map<String,Object> seal(Thread observed) {
        require(configured && Thread.holdsLock(engine) && active != null && !view.sealed,"motion-seal");
        active.seal(observed); view.sealed=true;
        if(MinimalMotionObservation.failure()!=null)latch(MinimalMotionObservation.failure());
        if(active.failure()!=null)latch(active.failure());
        Map<String,Object> out=new LinkedHashMap<String,Object>();
        out.put("phase",phase); out.put("enabled",enabled); out.put("entered",active.entered()); out.put("exited",active.exited());
        out.put("firstEntryNanos",Long.toString(active.firstEntryNanos())); out.put("lastExitNanos",Long.toString(active.lastExitNanos()));
        out.put("threadId",observed == null ? null : Long.toString(observed.getId())); out.put("joined",observed != null && !observed.isAlive());
        try {out.put("minimalObservation",MinimalMotionObservation.finish());}catch(Exception error){latch(error);}
        out.put("fields",FIELDS.clone()); out.put("enumNames",view.enumNames.clone()); out.put("rows",active.rows());
        Throwable error=failure(); out.put("firstError",error == null ? null : error.getClass().getName()+":"+error.getMessage());
        return out;
    }
    /** Rechecked after final UI teardown too; never erase a sealed phase's late error. */
    public static synchronized Map<String,Object> finish() {
        require(configured && Thread.holdsLock(engine),"motion-finish-monitor");
        if(active!=null && active.failure()!=null)latch(active.failure());
        Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("lastPhase",phase);out.put("sealed",view.sealed);
        Throwable error=sessionFirst;out.put("firstError",error==null?null:error.getClass().getName()+":"+error.getMessage());return out;
    }
    private static void require(boolean condition,String message) { if (!condition) throw new IllegalStateException(message); }
    private static Class<?> load(ClassLoader loader,String name) throws Exception {
        Class<?> c=Class.forName(name,false,loader); require(c.getClassLoader() == loader && Modifier.isPublic(c.getModifiers()),"motion-owner:"+name); return c;
    }
    private static Field field(Class<?> owner,String name,Class<?> type,boolean stat) throws Exception {
        Field f=owner.getField(name); require(f.getType() == type && Modifier.isPublic(f.getModifiers())
            && Modifier.isStatic(f.getModifiers()) == stat,"motion-field:"+owner.getName()+"."+name); return f;
    }
    private static Method method(Class<?> owner,String name,Class<?> result,Class<?>... parameters) throws Exception {
        Method m=owner.getMethod(name,parameters); require(m.getReturnType() == result && Modifier.isPublic(m.getModifiers()),"motion-method:"+name); return m;
    }
    private static long bits(Field f,Object object) throws Exception { return Float.floatToRawIntBits(f.getFloat(object)); }
    private static final class NativeView implements StepTrace.View {
        final Object engine,unit,owner,definition,controller,moveEnum;
        final Field bu,bv,i,g,bq,ej,x,y,ci,table,ownerField,queue,waypointEnum,wx,wy,count;
        final Method q,waypoint;
        final String[] enumNames;
        Object command; boolean sealed=true;
        NativeView(ClassLoader loader,Object engine,Object unit,Object owner,Object definition) throws Exception {
            this.engine=engine; this.unit=unit; this.owner=owner; this.definition=definition;
            Class<?> k=load(loader,"com.corrodinggames.rts.gameFramework.k"), simulation=load(loader,"com.corrodinggames.rts.game.i"), ce=load(loader,"com.corrodinggames.rts.game.units.ce"),
                bp=load(loader,"com.corrodinggames.rts.game.units.bp"), ah=load(loader,"com.corrodinggames.rts.gameFramework.ah"),
                p=load(loader,"com.corrodinggames.rts.game.p"), c=load(loader,"com.corrodinggames.rts.gameFramework.c"),
                en=load(loader,"com.corrodinggames.rts.game.units.en"), eo=load(loader,"com.corrodinggames.rts.game.units.eo");
            require(k.isInstance(engine) && engine.getClass()==simulation && bp.isInstance(unit) && p.isInstance(owner) && definition != null,"motion-objects");
            bu=field(k,"bu",int.class,false); bv=field(k,"bv",int.class,false); i=field(simulation,"I",float.class,false);
            g=field(simulation,"G",float.class,false); bq=field(k,"bq",float.class,false);
            ej=field(ah,"ej",long.class,false); x=field(ah,"eq",float.class,false); y=field(ah,"er",float.class,false); ci=field(ce,"ci",float.class,false);
            table=field(ce,"bG",load(loader,"com.corrodinggames.rts.gameFramework.utility.x"),true); ownerField=field(ce,"bZ",p,false);
            controller=field(k,"cc",c,false).get(engine); queue=field(c,"b",java.util.ArrayList.class,false);
            q=method(ce,"q",load(loader,"com.corrodinggames.rts.game.units.el")); waypoint=method(bp,"ap",en);
            waypointEnum=field(en,"a",eo,false); wx=field(en,"e",float.class,false); wy=field(en,"f",float.class,false); count=field(bp,"O",int.class,false);
            moveEnum=field(eo,"a",eo,true).get(null); Object[] enums=eo.getEnumConstants(); require(enums != null,"motion-waypoint-enum");
            enumNames=new String[enums.length]; for(int n=0;n<enums.length;n++) enumNames[n]=((Enum<?>)enums[n]).name();
        }
        public void capture(long[] row,int o) throws Exception {
            row[o++]=bu.getInt(engine); row[o++]=bv.getInt(engine); row[o++]=bits(i,engine); row[o++]=bits(g,engine); row[o++]=bits(bq,engine);
            row[o++]=ej.getLong(unit); row[o++]=bits(x,unit); row[o++]=bits(y,unit); row[o++]=bits(ci,unit);
            List<?> units=(List<?>)table.get(null); row[o++]=units.size(); boolean sole=units.size()==1 && units.get(0)==unit; row[o++]=sole?1:0;
            boolean sameOwner=ownerField.get(unit)==owner,sameDef=q.invoke(unit,EMPTY)==definition;
            row[o++]=sameOwner?1:0; row[o++]=sameDef?1:0;
            List<?> commands=(List<?>)queue.get(controller); int same=0,foreign=0;
            for(Object item:commands) { if(item==command) same++; else foreign++; }
            row[o++]=commands.size(); row[o++]=same; row[o++]=foreign;
            Object wp=waypoint.invoke(unit,EMPTY); row[o++]=wp==null?0:1;
            Object kind=wp==null?null:waypointEnum.get(wp); row[o++]=kind==moveEnum?1:0; row[o++]=kind==null?-1:((Enum<?>)kind).ordinal();
            row[o++]=wp==null?0:bits(wx,wp); row[o++]=wp==null?0:bits(wy,wp); row[o++]=count.getInt(unit);
            MinimalMotionObservation.capture(row,o);
            require(sole && sameOwner && sameDef,"motion-world-identity"); require(foreign==0 && same<=1,"motion-foreign-command");
        }
    }
}
