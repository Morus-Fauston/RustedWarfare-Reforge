package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonValue;

/** TEST-only one-Move lifecycle for fixed sandbox goals with open normal-clear areas.
 * Caller validates ordered actual step/action/world identity and fixed content qualification. */
public final class SandboxNormalCompletionOracle {
    private final double targetX,targetY;
    private double x,y,speed,heading,steering,nearTime;
    private boolean normal,failed;
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException("Normal completion: "+message);}
    private static double number(JsonValue value,String key){
        require(value!=null&&value.has(key)&&value.get(key).isNumber()&&Double.isFinite(value.getDouble(key)),"invalid "+key);
        return value.getDouble(key);
    }
    private boolean goal(JsonValue value){
        JsonValue goal=value.get("moveTarget");
        return goal!=null&&goal.isObject()&&number(goal,"x")==targetX&&number(goal,"y")==targetY;
    }
    private void remember(JsonValue unit){
        x=number(unit.get("position"),"x");y=number(unit.get("position"),"y");speed=number(unit,"speed");
        heading=number(unit,"heading");steering=number(unit,"angularVelocity");
        require(speed>=0&&speed<=1.1,"invalid speed");
    }
    public SandboxNormalCompletionOracle(JsonValue target,JsonValue afterMove){
        targetX=number(target,"x");targetY=number(target,"y");
        require(goal(afterMove)&&afterMove.get("pathPoints")!=null&&afterMove.get("pathPoints").isArray()&&afterMove.get("pathPoints").size>0,"new Move not active");
        remember(afterMove);
    }
    public void observe(JsonValue unit,JsonValue events,boolean advancing){
        try{require(!failed,"earlier failure latched");observeChecked(unit,events,advancing);}
        catch(RuntimeException failure){failed=true;throw failure;}
    }
    private void observeChecked(JsonValue unit,JsonValue events,boolean advancing){
        double nx=number(unit.get("position"),"x"),ny=number(unit.get("position"),"y"),ns=number(unit,"speed");
        double nh=number(unit,"heading"),ncg=number(unit,"angularVelocity");
        require(events!=null&&events.isArray(),"missing events");
        int arrivals=0;
        if(advancing)for(JsonValue event:events)if(event.getString("unitId","").equals("u1")){
            String kind=event.getString("kind","");
            require(!kind.equals("movement-cancelled")&&!kind.equals("path-unreachable"),"cancellation cannot replace normal completion");
            if(kind.equals("movement-arrived"))arrivals++;
        }
        require(arrivals<=1&&(!normal||arrivals==0),"duplicate normal event");
        if(advancing&&!normal){
            double dx=x-targetX,dy=y-targetY;if(dx*dx+dy*dy<1681)nearTime+=1;
            if(arrivals==1){double radius=nearTime>340?36:nearTime>240?16:7;
                require(dx*dx+dy*dy<radius*radius,"normal event outside strict entry radius");normal=true;}
        }
        JsonValue path=unit.get("pathPoints"),target=unit.get("moveTarget");
        require(path!=null&&path.isArray()&&target!=null,"missing path/target");
        if(normal){
            require(target.isNull()&&path.size==0,"goal/path restored after normal clear");
            require(nh==heading&&ncg==steering,"normal clear changed heading/retained steering");
            if(advancing){
                require(Math.abs(ns-Math.max(speed-.187,0))<=1e-9,"invalid proportional brake");
                double theta=Math.toRadians(heading);
                require(Math.abs(nx-x-Math.sin(theta)*ns)<=1e-9&&Math.abs(ny-y+Math.cos(theta)*ns)<=1e-9,"invalid open post-clear displacement");
                require(unit.getString("movementState","").equals(ns==0?"idle":"decelerating"),"invalid cleared movement state");
                if(speed==0)require(ns==0&&nx==x&&ny==y,"stopped normal unit drifted");
            }
        }else require(goal(unit)&&path.size>0,"goal cleared/replaced without actual normal event");
        if(!advancing)require(nx==x&&ny==y&&ns==speed&&nh==heading&&ncg==steering,"motion changed without a tick");
        remember(unit);
    }
    public boolean normalCleared(){return !failed&&normal;}
    public boolean stopped(){return !failed&&normal&&speed==0;}
}
