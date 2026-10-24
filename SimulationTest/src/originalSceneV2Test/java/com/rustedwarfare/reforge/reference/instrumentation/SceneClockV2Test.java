package com.rustedwarfare.reforge.reference.instrumentation;

import com.rustedwarfare.reforge.reference.motion.NativeMotionTrace;
import com.rustedwarfare.reforge.reference.motion.StepTrace;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Actual recorder/cursor boundary fixtures; no original classes are loaded. */
public final class SceneClockV2Test {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static ScenePlan plan(String id) {
        Map<String,String> request=new LinkedHashMap<String,String>();
        request.put("schemaVersion","1");request.put("profile","r17.O.v2."+id);request.put("requestId","clock-v2-test");
        request.put("runId","00000000-0000-4000-8000-000000000001");
        request.put("targetApkSha256",InitialProtocol.MOTION_TARGET_HASH);
        request.put("testApkSha256",new String(new char[64]).replace('\0','a'));
        request.put("signerSha256",InitialProtocol.SIGNER_HASH);return ScenePlan.from(request);
    }
    public static void main(String[] args) throws Exception {
        final Object engine=new Object();final boolean[] normal={false};
        Field schema=NativeMotionTrace.class.getDeclaredField("FIELDS");schema.setAccessible(true);final String[] fields=(String[])schema.get(null);
        check(fields.length==64);
        StepTrace trace=new StepTrace(engine,Thread.class,new StepTrace.View(){
            private void put(long[] row,int offset,String name,long value){row[offset+Arrays.asList(fields).indexOf(name)]=value;}
            public void capture(long[] row,int offset){
                put(row,offset,"effectiveIBits",Float.floatToRawIntBits(1));
                put(row,offset,"eqBits",Float.floatToRawIntBits(490));put(row,offset,"erBits",Float.floatToRawIntBits(200));
                put(row,offset,"normalBeforeCount",normal[0]?1:0);put(row,offset,"normalAfterCount",normal[0]?1:0);
            }
        },true,512,fields.length,0);
        synchronized(engine){trace.begin();}
        Field active=NativeMotionTrace.class.getDeclaredField("active");active.setAccessible(true);active.set(null,trace);
        SceneClock clock=new SceneClock(NativeMotionTrace.class,NativeMotionTrace.class.getClassLoader());
        trace.before(engine,1);check(clock.poll()==0);trace.after(engine,1);check(clock.poll()==1 && clock.poll()==1);
        for(int i=0;i<19;i++){trace.before(engine,1);trace.after(engine,1);clock.poll();}
        // Empty target plus a stationary position is insufficient without a normal branch marker.
        check(!clock.terminalTail(plan("OM01E")));
        normal[0]=true;trace.before(engine,1);trace.after(engine,1);clock.poll();normal[0]=false;
        check(!clock.terminalTail(plan("OM01E"))); // A marker cannot borrow an earlier unqualified tail.
        for(int i=0;i<18;i++){trace.before(engine,1);trace.after(engine,1);clock.poll();}
        check(clock.terminalTail(plan("OM01E"))); // Ten units from target; the old radius4 gate is gone.
        check(!clock.terminalTail(plan("OM08"))); // Always retain the 600q enclosed-goal reference.
        check(clock.terminalTail(plan("OM05")));
        active.set(null,null);boolean refused=false;try{clock.poll();}catch(IllegalStateException expected){refused=true;}check(refused);
        StepTrace wrongWidth=new StepTrace(engine,Thread.class,new StepTrace.View(){public void capture(long[] row,int offset){}},true,512,22,0);
        synchronized(engine){wrongWidth.begin();}active.set(null,wrongWidth);
        SceneClock wrong=new SceneClock(NativeMotionTrace.class,NativeMotionTrace.class.getClassLoader());
        refused=false;try{wrong.poll();}catch(IllegalStateException expected){refused=true;}check(refused);active.set(null,null);
        System.out.println("SceneClockV2Test PASS: schema-derived q, completed-only cursor, qualified terminal and old-width rejection");
    }
}
