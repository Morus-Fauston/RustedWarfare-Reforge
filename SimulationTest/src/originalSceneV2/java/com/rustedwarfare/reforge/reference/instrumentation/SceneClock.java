package com.rustedwarfare.reforge.reference.instrumentation;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** O.v2 cursor: schema-derived offsets and branch-qualified terminal tails. */
final class SceneClock {
    private final Field active,data,exited,enabled,stride,capacity,first;
    private final Object trace;
    private final String[] fields;
    private final int width;
    private int consumed;
    private double q;
    private double terminalQ=Double.NaN,terminalX,terminalY;
    private boolean normalSeen;
    SceneClock(Class<?> observer,ClassLoader loader) throws Exception {
        require(observer.getName().equals("com.rustedwarfare.reforge.reference.motion.NativeMotionTrace") && observer.getClassLoader()==loader,"clock-observer-owner");
        Class<?> recorder=Class.forName("com.rustedwarfare.reforge.reference.motion.StepTrace",false,loader);
        require(recorder.getClassLoader()==loader,"clock-recorder-loader");
        fields=((String[])field(observer,"FIELDS",String[].class,true).get(null)).clone();width=3+fields.length;
        require(fields.length==64 && new HashSet<String>(Arrays.asList(fields)).size()==fields.length,"clock-schema");
        for(String key:new String[]{"effectiveIBits","eqBits","erBits","waypointPresent","waypointCountO","normalBeforeCount","normalAfterCount"})index(key);
        active=field(observer,"active",recorder,true);require(Modifier.isVolatile(active.getModifiers()),"clock-active-volatile");
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
    private int index(String name){int result=Arrays.asList(fields).indexOf(name);require(result>=0,"clock-field-missing:"+name);return result;}
    private long post(long[] row,String name){return row[width+3+index(name)];}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    double poll() throws Exception {
        require(active.get(null)==trace,"clock-active-changed");
        synchronized(trace) {
            Throwable error=(Throwable)first.get(trace);if(error!=null)throw new IllegalStateException("clock-recorder-error",error);
            require(enabled.getBoolean(trace) && stride.getInt(trace)==width && capacity.getInt(trace)==512,"clock-recorder-shape");
            long[][] rows=(long[][])data.get(trace);int completed=exited.getInt(trace);
            require(rows.length==512 && completed>=consumed && completed<=512,"clock-completed-count");
            while(consumed<completed) {
                long[] row=rows[consumed];require(row.length==2*width && row[0]>0 && row[width]>=row[0],"clock-completed-row");
                float delta=Float.intBitsToFloat((int)post(row,"effectiveIBits"));
                require(!Float.isNaN(delta) && !Float.isInfinite(delta) && delta>=0,"clock-invalid-I");q+=delta;consumed++;
                if(post(row,"normalBeforeCount")==1 && post(row,"normalAfterCount")==1 && !normalSeen){normalSeen=true;terminalQ=Double.NaN;}
                double x=Float.intBitsToFloat((int)post(row,"eqBits")),y=Float.intBitsToFloat((int)post(row,"erBits"));
                boolean clear=post(row,"waypointPresent")==0 && post(row,"waypointCountO")==0;
                if(!clear)terminalQ=Double.NaN;
                else if(Double.isNaN(terminalQ) || Math.hypot(x-terminalX,y-terminalY)>.001){terminalQ=q;terminalX=x;terminalY=y;}
            }
            return q;
        }
    }
    boolean terminalTail(ScenePlan plan) {
        if(Double.isNaN(terminalQ) || q-terminalQ<18)return false;
        // OM08 always keeps the complete 600q reference, including unclassified fallback.
        return !plan.id.equals("OM08") && (plan.stop || normalSeen);
    }
    Map<String,Object> facts() {
        Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("completedRows",consumed);result.put("completedQ",q);
        result.put("source","read-only-schema-derived-StepTrace-completed-post-effectiveI");return result;
    }
}
