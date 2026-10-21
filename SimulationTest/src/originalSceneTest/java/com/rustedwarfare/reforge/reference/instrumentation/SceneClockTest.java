package com.rustedwarfare.reforge.reference.instrumentation;

import com.rustedwarfare.reforge.reference.motion.NativeMotionTrace;
import com.rustedwarfare.reforge.reference.motion.StepTrace;
import java.lang.reflect.Field;

/** Actual frozen recorder fixture, including an unfinished callback and a phase replacement. */
public final class SceneClockTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args) throws Exception {
        final Object engine=new Object();final float[] delta={1};
        StepTrace trace=new StepTrace(engine,Thread.class,new StepTrace.View(){
            public void capture(long[] row,int offset){row[offset+2]=Float.floatToRawIntBits(delta[0]);row[offset+6]=Float.floatToRawIntBits(500);row[offset+7]=Float.floatToRawIntBits(200);}
        },true,512,22,0);
        synchronized(engine){trace.begin();}
        // Test-only fixture publication; production SceneClock has no reflective writes.
        Field active=NativeMotionTrace.class.getDeclaredField("active");active.setAccessible(true);active.set(null,trace);
        SceneClock clock=new SceneClock(NativeMotionTrace.class,NativeMotionTrace.class.getClassLoader());
        check(clock.poll()==0);trace.before(engine,1);check(clock.poll()==0);
        trace.after(engine,1);check(clock.poll()==1 && clock.poll()==1);
        delta[0]=.5f;trace.before(engine,.5f);check(clock.poll()==1);trace.after(engine,.5f);check(clock.poll()==1.5);
        for(int i=0;i<36;i++){trace.before(engine,.5f);trace.after(engine,.5f);clock.poll();}
        java.util.Map<String,String> request=new java.util.LinkedHashMap<String,String>();
        request.put("schemaVersion","1");request.put("profile","r22.O.v1.OM01E");request.put("requestId","clock-test");
        request.put("runId","00000000-0000-4000-8000-000000000001");
        request.put("targetApkSha256","6eb0d6e2a7fb7f8906200253bc8f2c122cc672881968e693587813b171d19ece");
        request.put("testApkSha256",new String(new char[64]).replace('\0','a'));
        request.put("signerSha256","efcd2943ba1d0793b8283a09bab852311f52ab6771029c209f612cd1d990513d");
        check(clock.terminalTail(ScenePlan.from(request)));
        active.set(null,null);boolean refused=false;try{clock.poll();}catch(IllegalStateException expected){refused=true;}check(refused);
        StepTrace fault=new StepTrace(engine,Thread.class,new StepTrace.View(){public void capture(long[] row,int offset){}},true,512,22,1);
        synchronized(engine){fault.begin();}active.set(null,fault);
        SceneClock faultClock=new SceneClock(NativeMotionTrace.class,NativeMotionTrace.class.getClassLoader());
        fault.before(engine,1);fault.after(engine,1);refused=false;
        try{faultClock.poll();}catch(IllegalStateException expected){refused=expected.getCause()!=null && "injected-observer-failure".equals(expected.getCause().getMessage());}
        check(refused);active.set(null,null);
        System.out.println("SceneClockTest passed: completed-only q, no duplicate sum, fixed terminal tail and phase identity");
    }
}
