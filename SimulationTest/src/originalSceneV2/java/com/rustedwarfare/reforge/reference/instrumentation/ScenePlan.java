package com.rustedwarfare.reforge.reference.instrumentation;

import java.util.Map;
import java.util.LinkedHashMap;

/** Separate S07 scene entry. The original S06 request protocol is unchanged. */
final class ScenePlan {
    final String id;
    final float x,y,ci,targetX,targetY;
    final boolean stationary,stop;
    private ScenePlan(String id,float x,float y,float ci,float tx,float ty,boolean stationary,boolean stop) {
        this.id=id;this.x=x;this.y=y;this.ci=ci;targetX=tx;targetY=ty;this.stationary=stationary;this.stop=stop;
    }
    static void validate(Map<String,String> request) {
        if(request==null)throw new IllegalArgumentException("scene-request-null");
        plan(request.get("profile"));
        Map<String,String> old=new LinkedHashMap<String,String>(request);
        old.put("profile","r21.p001.normal-motion.on.v1");
        // Preserve all seven-key, run UUID, target, signer and test identity gates.
        InitialProtocol.validate(old);
    }
    static ScenePlan from(Map<String,String> request) { validate(request);return plan(request.get("profile")); }
    static String motionMode(Map<String,String> request) {
        // Failure transport calls this even when request validation failed; never mask that first error.
        try{plan(request==null?null:request.get("profile"));return "on";}
        catch(IllegalArgumentException invalid){return null;}
    }
    private static ScenePlan plan(String profile) {
        if(profile==null || !profile.startsWith("r17.O.v2."))throw new IllegalArgumentException("scene-profile");
        String id=profile.substring("r17.O.v2.".length());
        float x=300,y=200,ci=0,tx=300,ty=200;boolean stationary=false,stop=false;
        switch(id) {
            case "OM00":stationary=true;break;
            case "OM01E":tx=500;break;
            case "OM01W":ci=180;tx=100;break;
            case "OM02S":ci=90;ty=350;break;
            case "OM02N":ci=-90;ty=50;break;
            case "OM03SE":ci=45;tx=400;ty=300;break;
            case "OM03NW":ci=-135;tx=200;ty=100;break;
            case "OM04":ty=350;break;
            case "OM05":tx=500;stop=true;break;
            case "OM06":tx=360;break;
            case "OM07":x=150;tx=450;break;
            case "OM08":x=150;tx=410;break;
            default:throw new IllegalArgumentException("scene-case-outside-fixed-O-v2");
        }
        return new ScenePlan(id,x,y,ci,tx,ty,stationary,stop);
    }
    long[] durations() { return stationary?new long[]{2000}:stop?new long[]{2000,6500,6500}:new long[]{6500,6500}; }
    double minimumPhaseQ(int phase) { return stationary?18:stop?(phase==0?30:phase==1?300:600):phase==0?270:600; }
    boolean blocked(int tileX,int tileY) {
        int px=tileX*20,py=tileY*20;
        if(id.equals("OM07"))return px>=280 && px<320 && py>=120 && py<280;
        if(id.equals("OM08"))return px>=360 && px<460 && py>=140 && py<260 &&
            (py<160 || py>=240 || px<380 || px>=440);
        return false;
    }
}
