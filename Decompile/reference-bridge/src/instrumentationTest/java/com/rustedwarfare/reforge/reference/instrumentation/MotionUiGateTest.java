package com.rustedwarfare.reforge.reference.instrumentation;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MotionUiGateTest {
    private static void rejected(Map<String,Object> state) {
        try { MotionUiGate.requireReady(state); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("blocked native state admitted");
    }
    public static void main(String[] args) throws Throwable {
        Map<String,Object> s=new LinkedHashMap<String,Object>();
        String[] flags={"gameThreadRunning","ap","bE","bm","interfacePaused","networked","networkBlocked"};
        for(String field:flags)s.put(field,false);
        s.put("bD",true);s.put("viewActivityIsContext",true);s.put("expectedActivitySame",true);s.put("surfaceExists",true);s.put("activityClass","com.corrodinggames.rts.appFramework.InGameActivity");
        MotionUiGate.requireReady(s);
        // Actual first-trial path: MainMenu left ap=true after a real map set bE=false.
        s.put("ap",true);s.put("activityClass","com.corrodinggames.rts.appFramework.MainMenuActivity");rejected(s);
        s.put("ap",false);rejected(s);s.put("activityClass","com.corrodinggames.rts.appFramework.InGameActivity");
        for(String field:flags){s.put(field,true);rejected(s);s.put(field,false);}
        s.remove("bm");rejected(s);s.put("bm",false);s.put("bD",false);rejected(s);s.put("bD",true);
        for(String field:new String[]{"viewActivityIsContext","expectedActivitySame","surfaceExists"}) {s.put(field,false);rejected(s);s.put(field,true);}
        MotionUiGate.requireReady(s);
        final Map<String,Object> state=s;
        class Clock implements MotionUiGate.Poll {
            long tick;int reads;boolean layout=true,replace;
            public long now(){return tick;}
            public void pause(){tick++;}
            public Map<String,Object> read(){reads++;state.put("surfaceExists",layout && reads>=3);state.put("expectedActivitySame",!replace);return state;}
        }
        Clock delayed=new Clock();MotionUiGate.awaitReady(delayed,5);if(delayed.reads!=3)throw new AssertionError("did not wait for layout");
        Clock absent=new Clock();absent.layout=false;
        try{MotionUiGate.awaitReady(absent,3);throw new AssertionError("missing layout accepted");}catch(IllegalArgumentException expected){}
        Clock replaced=new Clock();replaced.replace=true;
        try{MotionUiGate.awaitReady(replaced,3);throw new AssertionError("replacement accepted");}catch(IllegalArgumentException expected){}
        System.out.println("motion UI gate: pauses/layout-ready/layout-timeout/replacement passed");
    }
}
