package com.rustedwarfare.reforge.reference.instrumentation;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only cursor over the pinned, self-owned recorder; never calls the engine under its monitor. */
final class SceneClock {
    private final Field active,data,exited,enabled,stride,capacity,first;
    private final Object trace;
    private int consumed;
    private double q;
    private double terminalQ=Double.NaN,terminalX,terminalY;
    SceneClock(Class<?> observer,ClassLoader loader) throws Exception {
        require(observer.getName().equals("com.rustedwarfare.reforge.reference.motion.NativeMotionTrace") && observer.getClassLoader()==loader,"clock-observer-owner");
        Class<?> recorder=Class.forName("com.rustedwarfare.reforge.reference.motion.StepTrace",false,loader);
        require(recorder.getClassLoader()==loader,"clock-recorder-loader");
        active=field(observer,"active",recorder,true);
        require(Modifier.isVolatile(active.getModifiers()),"clock-active-volatile");
        trace=active.get(null);require(trace!=null && trace.getClass()==recorder,"clock-active");
        data=field(recorder,"data",long[][].class,false);exited=field(recorder,"exited",int.class,false);
        enabled=field(recorder,"enabled",boolean.class,false);stride=field(recorder,"stride",int.class,false);
        capacity=field(recorder,"capacity",int.class,false);first=field(recorder,"first",Throwable.class,false);
    }
    private static Field field(Class<?> owner,String name,Class<?> type,boolean stat) throws Exception {
        Field f=owner.getDeclaredField(name);
        require(f.getType()==type && Modifier.isPrivate(f.getModifiers()) && Modifier.isStatic(f.getModifiers())==stat,"clock-field:"+name);
        f.setAccessible(true);return f;
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    double poll() throws Exception {
        require(active.get(null)==trace,"clock-active-changed");
        synchronized(trace) {
            Throwable error=(Throwable)first.get(trace);
            if(error!=null)throw new IllegalStateException("clock-recorder-error",error);
            require(enabled.getBoolean(trace) && stride.getInt(trace)==25 && capacity.getInt(trace)==512,"clock-recorder-shape");
            long[][] rows=(long[][])data.get(trace);int completed=exited.getInt(trace);
            require(rows.length==512 && completed>=consumed && completed<=512,"clock-completed-count");
            while(consumed<completed) {
                long[] row=rows[consumed];require(row.length==50 && row[0]>0 && row[25]>=row[0],"clock-completed-row");
                float delta=Float.intBitsToFloat((int)row[30]);
                require(!Float.isNaN(delta) && !Float.isInfinite(delta) && delta>=0,"clock-invalid-I");
                q+=delta;consumed++;
                double x=Float.intBitsToFloat((int)row[34]),y=Float.intBitsToFloat((int)row[35]);
                boolean clear=row[41]==0 && row[44]==0;
                if(!clear)terminalQ=Double.NaN;
                else if(Double.isNaN(terminalQ) || Math.hypot(x-terminalX,y-terminalY)>.001){terminalQ=q;terminalX=x;terminalY=y;}
            }
            return q;
        }
    }
    boolean terminalTail(ScenePlan plan) {
        if(Double.isNaN(terminalQ) || q-terminalQ<18)return false;
        return plan.stop || plan.id.equals("OM08") || Math.hypot(terminalX-plan.targetX,terminalY-plan.targetY)<=4;
    }
    Map<String,Object> facts() {
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        result.put("completedRows",consumed);result.put("completedQ",q);
        result.put("source","read-only-pinned-own-StepTrace-completed-post-effectiveI");return result;
    }
}
