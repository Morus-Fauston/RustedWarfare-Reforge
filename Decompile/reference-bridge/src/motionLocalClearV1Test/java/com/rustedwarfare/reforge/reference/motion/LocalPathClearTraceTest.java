package com.rustedwarfare.reforge.reference.motion;

import java.util.Map;
import java.util.List;

/** Explicit synthetic recorder controls; this is neither ART nor a gameplay fixture. */
public final class LocalPathClearTraceTest {
    private static int tests,failures;
    private static void check(boolean good) { if(!good)throw new AssertionError(); }
    private static void reject(Runnable action) { try {action.run();}catch(IllegalStateException expected){return;}throw new AssertionError("corrupt observation accepted"); }
    private static void run(String name,Runnable action) { tests++;try{action.run();}catch(Throwable error){failures++;System.err.println(name+": "+error);} }
    // ej/token/target x,y/kind/position x,y/O/s/afPresent; after ej/token/O/x,y.
    private static long[] before() {return new long[]{55,1,Float.floatToRawIntBits(410f),Float.floatToRawIntBits(200f),0,
        Float.floatToRawIntBits(347f),Float.floatToRawIntBits(210f),1,0,0};}
    private static long[] after() {return new long[]{55,0,0,Float.floatToRawIntBits(347f),Float.floatToRawIntBits(210f)};}
    private static LocalPathClearTrace active() {LocalPathClearTrace t=new LocalPathClearTrace();t.beginPhase(0);t.beginCall(0,91);return t;}
    @SuppressWarnings("unchecked") private static Map<String,Object> event(Map<String,Object> report) {return ((List<Map<String,Object>>)report.get("events")).get(0);}
    public static void main(String[] ignored) {
        run("positive exact call binding and defensive copy",()->{
            LocalPathClearTrace t=active();long[] b=before();t.before(b);b[1]=99;t.after(after());t.endCall();Map<String,Object> report=t.finish();
            check(Integer.valueOf(1).equals(report.get("completedCallCount")));check(Integer.valueOf(1).equals(report.get("eventCount")));
            Map<String,Object> e=event(report);check(Integer.valueOf(0).equals(e.get("phase")));check(Integer.valueOf(0).equals(e.get("callIndex")));
            check(Long.valueOf(91).equals(e.get("threadId")));check(Long.valueOf(1).equals(e.get("oldToken")));check(Long.valueOf(55).equals(e.get("unitEj")));
            check(Long.valueOf(0).equals(e.get("afterToken")));check(Long.valueOf(0).equals(e.get("afterCountO")));
        });
        run("512 checked calls no marker is an explicit empty phase",()->{
            LocalPathClearTrace t=new LocalPathClearTrace();t.beginPhase(0);for(int i=0;i<512;i++){t.beginCall(i,91);t.endCall();}
            Map<String,Object> r=t.finish();check(Integer.valueOf(512).equals(r.get("completedCallCount")));check(Integer.valueOf(0).equals(r.get("eventCount")));
        });
        run("after without before",()->{LocalPathClearTrace t=active();reject(()->t.after(after()));});
        run("duplicate before",()->{LocalPathClearTrace t=active();t.before(before());reject(()->t.before(before()));});
        run("duplicate after",()->{LocalPathClearTrace t=active();t.before(before());t.after(after());reject(()->t.after(after()));});
        run("unpaired end",()->{LocalPathClearTrace t=active();t.before(before());reject(t::endCall);});
        run("wrong unit or still active after",()->{for(int index:new int[]{0,1,2}){LocalPathClearTrace t=active();t.before(before());long[] a=after();a[index]=99;reject(()->t.after(a));}});
        run("wrong pre guards",()->{for(int index:new int[]{1,7,8,9}){LocalPathClearTrace t=active();long[] b=before();b[index]=index==1?0:index==7?2:1;reject(()->t.before(b));}});
        run("wrong call index or changed thread",()->{LocalPathClearTrace t=new LocalPathClearTrace();t.beginPhase(0);reject(()->t.beginCall(1,91));
            LocalPathClearTrace u=active();u.endCall();reject(()->u.beginCall(1,92));});
        run("phase change cannot discard an inflight call",()->{LocalPathClearTrace t=active();reject(()->t.beginPhase(1));});
        run("finish before call end or twice",()->{LocalPathClearTrace t=active();reject(t::finish);LocalPathClearTrace u=active();u.endCall();u.finish();reject(u::finish);});
        run("new phase uses new zero index and fresh event buffer",()->{LocalPathClearTrace t=active();t.before(before());t.after(after());t.endCall();t.finish();
            t.beginPhase(1);t.beginCall(0,92);t.endCall();Map<String,Object> r=t.finish();check(Integer.valueOf(0).equals(r.get("eventCount")));check(Integer.valueOf(1).equals(r.get("phase")));});
        run("17th event rejects rather than truncates",()->{LocalPathClearTrace t=new LocalPathClearTrace();t.beginPhase(0);
            for(int i=0;i<16;i++){t.beginCall(i,91);t.before(before());t.after(after());t.endCall();}
            t.beginCall(16,91);t.before(before());t.after(after());reject(t::endCall);reject(t::finish);});
        run("513th call rejects rather than wrapping",()->{LocalPathClearTrace t=new LocalPathClearTrace();t.beginPhase(0);
            for(int i=0;i<512;i++){t.beginCall(i,91);t.endCall();}reject(()->t.beginCall(512,91));});
        System.out.println("{\"kind\":\"synthetic-local-clear-recorder-controls\",\"tests\":"+tests+",\"failures\":"+failures+"}");
        if(failures!=0)throw new AssertionError("recorder controls failed: "+failures);
    }
}
