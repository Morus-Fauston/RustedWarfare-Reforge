package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import java.util.List;

/** Literal lifecycle predicates only; repeated active states are not physical trajectory claims. */
public final class SandboxNormalCompletionOracleTest {
    private static JsonValue json(String value){return new JsonReader().parse(value);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static JsonValue unit(double x,double speed,double heading,double cg,boolean active){
        return json("{unitId:u1,position:{x:"+x+",y:110},heading:"+heading+",angularVelocity:"+cg+",speed:"+speed+
            ",moveTarget:"+(active?"{x:350,y:110}":"null")+",pathPoints:"+(active?"[{x:350,y:110}]":"[]")+
            ",movementState:"+(active?"moving":speed==0?"idle":"decelerating")+"}");
    }
    private static final JsonValue EVENT=json("[{kind:movement-arrived,unitId:u1}]");
    private static void rejected(String label,Runnable test){
        try{test.run();}catch(IllegalArgumentException expected){return;}
        throw new AssertionError("Accepted normal lifecycle defect: "+label);
    }
    private static SandboxNormalCompletionOracle checker(double x){
        return new SandboxNormalCompletionOracle(json("{x:350,y:110}"),unit(x,1.1,90,2.25,true));
    }
    public static void main(String[] args){
        for(int[] boundary:new int[][]{{1,7},{240,7},{241,16},{340,16},{341,36}})for(boolean inside:new boolean[]{false,true}){
            double x=350-boundary[1]+(inside?.001:0);SandboxNormalCompletionOracle c=checker(x);
            for(int i=1;i<boundary[0];i++)c.observe(unit(x,1.1,90,2.25,true),json("[]"),true);
            final double first=x+.913;
            if(!inside){rejected("strict radius",()->c.observe(unit(first,.913,90,2.25,false),EVENT,true));require(!c.normalCleared()&&!c.stopped(),"Failure did not latch");}
            else{
                c.observe(unit(first,.913,90,2.25,false),EVENT,true);require(c.normalCleared()&&!c.stopped(),"Normal not yet stopped");
                double position=first,velocity=.913;
                for(int i=0;i<5;i++){velocity=Math.max(0,velocity-.187);position+=velocity;c.observe(unit(position,velocity,90,2.25,false),json("[]"),true);}
                require(c.stopped(),"Literal brake not stopped");
                for(int i=0;i<19;i++)c.observe(unit(position,0,90,2.25,false),json("[]"),true);
                c.observe(unit(position,0,90,2.25,false),EVENT,false);require(c.stopped(),"Repeated nonstep events must not be processed again");
                final double stopped=position;
                rejected("stopped drift",()->c.observe(unit(stopped+.01,0,90,2.25,false),json("[]"),true));require(!c.stopped(),"Late failure did not invalidate stopped");
            }
        }
        for(String kind:List.of("missing","duplicate","wrong-unit","cancel","unreachable","goal-retained","heading","steering","old-brake","position")){
            SandboxNormalCompletionOracle c=checker(344);
            JsonValue value=unit(344.913,.913,90,2.25,false),events=json("[{kind:movement-arrived,unitId:u1}]");
            switch(kind){
                case "missing":events=json("[]");break;
                case "duplicate":events.addChild(json("{kind:movement-arrived,unitId:u1}"));break;
                case "wrong-unit":events.get(0).get("unitId").set("u2");break;
                case "cancel":case "unreachable":events.addChild(json("{kind:"+(kind.equals("cancel")?"movement-cancelled":"path-unreachable")+",unitId:u1}"));break;
                case "goal-retained":value=unit(344.913,.913,90,2.25,true);break;
                case "heading":value.get("heading").set(91L,null);break;
                case "steering":value.get("angularVelocity").set(0L,null);break;
                case "old-brake":value.get("speed").set(.93,null);break;
                case "position":value.get("position").get("x").set(344.923,null);break;
            }
            JsonValue bad=value,badEvents=events;rejected(kind,()->c.observe(bad,badEvents,true));require(!c.normalCleared(),"Invalid normal stayed qualified");
        }
        SandboxNormalCompletionOracle unchanged=checker(334.001);
        for(int i=0;i<1000;i++)unchanged.observe(unit(334.001,1.1,90,2.25,true),json("[]"),false);
        rejected("nonsteps cannot accumulate near time",()->unchanged.observe(unit(334.914,.913,90,2.25,false),EVENT,true));
        SandboxNormalCompletionOracle outside=checker(309);
        for(int i=0;i<341;i++)outside.observe(unit(309,1.1,90,2.25,true),json("[]"),true);
        outside.observe(unit(334.001,1.1,90,2.25,true),json("[]"),true);
        rejected("strict41 cannot accumulate",()->outside.observe(unit(334.914,.913,90,2.25,false),EVENT,true));
        SandboxNormalCompletionOracle first=checker(334.001);
        for(int i=0;i<240;i++)first.observe(unit(334.001,1.1,90,2.25,true),json("[]"),true);
        SandboxNormalCompletionOracle replacement=checker(334.001);
        rejected("new Move resets timer",()->replacement.observe(unit(334.914,.913,90,2.25,false),EVENT,true));
        first.observe(unit(334.914,.913,90,2.25,false),EVENT,true);require(first.normalCleared(),"Same Move lost near time");
        System.out.println("PASS normal lifecycle literal boundaries, retained cg, six-step brake,18q idle, event/cause/motion negatives and per-Move timer");
    }
}
