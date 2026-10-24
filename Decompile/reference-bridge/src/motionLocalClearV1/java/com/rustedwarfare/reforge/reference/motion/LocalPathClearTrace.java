package com.rustedwarfare.reforge.reference.motion;

import java.util.Map;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Bounded observation storage only. It never holds or writes a game object. */
public final class LocalPathClearTrace {
    private int phase=-1,completed,index;
    private long threadId;
    private boolean inCall,sealed;
    private long[] before,after;
    private final List<Map<String,Object>> events=new ArrayList<Map<String,Object>>(16);
    private static void require(boolean good,String message) {if(!good)throw new IllegalStateException(message);}
    public void beginPhase(int next) {
        require(!inCall && next==phase+1 && next<4 && (phase<0 || sealed),"local-clear-phase");
        phase=next;completed=0;index=-1;threadId=0;sealed=false;before=null;after=null;events.clear();
    }
    public void beginCall(int next,long actualThreadId) {
        require(phase>=0 && !sealed && !inCall && next==completed && next<512 && actualThreadId>0 &&
            (threadId==0 || threadId==actualThreadId),"local-clear-call");
        threadId=actualThreadId;index=next;before=null;after=null;inCall=true;
    }
    private static void finite(long value) {
        require(value==(int)value && Float.isFinite(Float.intBitsToFloat((int)value)),"local-clear-nonfinite-bits");
    }
    public void before(long[] snapshot) {
        require(inCall && !sealed && before==null && after==null,"local-clear-before-order");
        require(snapshot!=null && snapshot.length==10 && snapshot[1]>0 && snapshot[4]>=0 && snapshot[7]==1 &&
            snapshot[8]==0 && snapshot[9]==0,"local-clear-before-state");
        for(int field:new int[]{2,3,5,6})finite(snapshot[field]);
        before=snapshot.clone();
    }
    public void after(long[] snapshot) {
        require(inCall && !sealed && before!=null && after==null,"local-clear-after-order");
        require(snapshot!=null && snapshot.length==5 && snapshot[0]==before[0] && snapshot[1]==0 && snapshot[2]==0,"local-clear-after-state");
        finite(snapshot[3]);finite(snapshot[4]);after=snapshot.clone();
    }
    public void endCall() {
        require(inCall && !sealed && (before==null)==(after==null),"local-clear-call-unpaired");
        if(before!=null) {
            require(events.size()<16,"local-clear-overflow");
            Map<String,Object> event=new LinkedHashMap<String,Object>();
            event.put("phase",phase);event.put("callIndex",index);event.put("threadId",threadId);event.put("branchId","bp.P@0297");
            String[] names={"unitEj","oldToken","targetXBits","targetYBits","kindOrdinal","beforeXBits","beforeYBits","beforeCountO","s","afPresent"};
            for(int i=0;i<names.length;i++)event.put(names[i],before[i]);
            event.put("afterUnitEj",after[0]);event.put("afterToken",after[1]);event.put("afterCountO",after[2]);
            event.put("afterXBits",after[3]);event.put("afterYBits",after[4]);event.put("beforeCount",1);event.put("afterCount",1);
            events.add(Collections.unmodifiableMap(event));
        }
        completed++;inCall=false;before=null;after=null;
    }
    public Map<String,Object> finish() {
        require(phase>=0 && !inCall && !sealed,"local-clear-finish");sealed=true;
        Map<String,Object> result=new LinkedHashMap<String,Object>();
        result.put("schemaVersion",1);result.put("phase",phase);result.put("completedCallCount",completed);result.put("eventCount",events.size());
        result.put("events",Collections.unmodifiableList(new ArrayList<Map<String,Object>>(events)));
        return Collections.unmodifiableMap(result);
    }
}
