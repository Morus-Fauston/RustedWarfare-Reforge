package com.rustedwarfare.reforge.reference.instrumentation;

import java.util.Map;

/** Stopped-state admission for the original InGameActivity, never a state mutator. */
final class MotionUiGate {
    interface Poll {
        Map<String,Object> read() throws Throwable;
        long now();
        void pause();
    }
    static Map<String,Object> awaitReady(Poll poll,long deadline) throws Throwable {
        for(;;){
            InitialProtocol.require(poll.now()<deadline,"motion-ui-layout-timeout");
            Map<String,Object> state=poll.read();requireAttached(state);
            InitialProtocol.require(poll.now()<deadline,"motion-ui-layout-timeout");
            if(Boolean.TRUE.equals(state.get("surfaceExists")))return state;
            InitialProtocol.require(Boolean.FALSE.equals(state.get("surfaceExists")),"motion-ui-surface-type");
            poll.pause();
        }
    }
    private static void requireAttached(Map<String,Object> state) {
        for (String field : new String[] {"gameThreadRunning", "ap", "bE", "bm", "interfacePaused", "networked", "networkBlocked"})
            InitialProtocol.require(Boolean.FALSE.equals(state.get(field)), "motion-ui-blocked:" + field);
        InitialProtocol.require(Boolean.TRUE.equals(state.get("bD")), "motion-ui-world-not-ready");
        InitialProtocol.require(Boolean.TRUE.equals(state.get("viewActivityIsContext")) && Boolean.TRUE.equals(state.get("expectedActivitySame")), "motion-ui-view");
        InitialProtocol.require("com.corrodinggames.rts.appFramework.InGameActivity".equals(state.get("activityClass")), "motion-ui-activity");
    }
    static void requireReady(Map<String,Object> state){requireAttached(state);InitialProtocol.require(Boolean.TRUE.equals(state.get("surfaceExists")),"motion-ui-view");}
}
