package com.rustedwarfare.reforge.reference.instrumentation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Public scene request/plan boundary; no Android or original simulation fixture. */
public final class ScenePlanTest {
    private static void check(boolean value) { if(!value) throw new AssertionError(); }
    public static void main(String[] args) {
        Map<String,String> request=new LinkedHashMap<String,String>();
        request.put("schemaVersion","1");request.put("profile","r22.O.v1.OM03NW");
        request.put("requestId","literal-plan-control");request.put("runId","00000000-0000-4000-8000-000000000001");
        request.put("targetApkSha256","6eb0d6e2a7fb7f8906200253bc8f2c122cc672881968e693587813b171d19ece");
        request.put("testApkSha256",new String(new char[64]).replace('\0','a'));
        request.put("signerSha256","efcd2943ba1d0793b8283a09bab852311f52ab6771029c209f612cd1d990513d");
        ScenePlan.validate(request);
        ScenePlan plan=ScenePlan.from(request);
        check(plan.id.equals("OM03NW") && plan.x==300f && plan.y==200f && plan.ci==-135f);
        check(plan.targetX==200f && plan.targetY==100f && !plan.stationary && !plan.stop);
        request.put("profile","r22.O.v1.OM00");check(ScenePlan.from(request).stationary);
        request.put("profile","r22.O.v1.OM05");check(ScenePlan.from(request).stop);
        request.put("profile","r22.O.v1.OM07");check(ScenePlan.from(request).x==150f);
        for(String wrong:new String[]{"r22.O.v1.UNKNOWN","r21.p001.normal-motion.on.v1","r22.O.v1.OM03NW.extra"}) {
            request.put("profile",wrong);boolean refused=false;
            try{ScenePlan.validate(request);}catch(IllegalArgumentException expected){refused=true;}
            check(refused);
        }
        request.put("profile","r22.O.v1.OM03NW");request.put("targetX","999");
        boolean refused=false;try{ScenePlan.validate(request);}catch(IllegalArgumentException expected){refused=true;}
        check(refused);System.out.println("ScenePlanTest passed: fixed approved cases and request refusal");
    }
}
