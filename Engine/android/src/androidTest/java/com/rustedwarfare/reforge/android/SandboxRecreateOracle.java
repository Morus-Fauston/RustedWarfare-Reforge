package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonValue;
import java.util.List;

/** TEST-only two-run recreation contract; worldId is deliberately scoped to its run. */
public final class SandboxRecreateOracle {
    private static void require(boolean value,String message) { if(!value)throw new IllegalArgumentException(message); }
    private static JsonValue field(JsonValue row,String name) {
        require(row!=null&&row.isObject()&&row.has(name),"Missing recreate field "+name);return row.get(name);
    }
    private static String string(JsonValue row,String name) { JsonValue v=field(row,name);require(v.isString(),"Not a string: "+name);return v.asString(); }
    private static long integer(JsonValue row,String name) { JsonValue v=field(row,name);require(v.isLong(),"Not an integer: "+name);return v.asLong(); }
    private static double number(JsonValue row,String name) {
        JsonValue v=field(row,name);require(v.isNumber()&&Double.isFinite(v.asDouble()),"Not finite: "+name);return v.asDouble();
    }
    private static boolean bool(JsonValue row,String name) {JsonValue v=field(row,name);require(v.isBoolean(),"Not boolean: "+name);return v.asBoolean();}
    private static void sampleIdentity(JsonValue sample) {
        require(string(sample,"scenarioId").equals("p002-knowledge")&&string(sample,"playerId").equals("p1")
            &&!string(sample,"worldId").isEmpty(),"Wrong recreation sample identity");
    }
    private static JsonValue unit(JsonValue sample) {
        sampleIdentity(sample);JsonValue units=field(sample,"units");
        require(units.isArray()&&units.size==1,"Expected one unique visible u1");JsonValue u=units.get(0);
        require(string(u,"unitId").equals("u1")&&string(u,"ownerPlayerId").equals("p1")&&!string(u,"typeId").isEmpty(),"Wrong recreated unit");
        string(u,"movementState");
        number(field(u,"position"),"x");number(field(u,"position"),"y");number(u,"heading");
        require(number(u,"speed")>=0,"Negative speed");number(u,"angularVelocity");
        JsonValue path=field(u,"pathPoints");require(path.isArray(),"Invalid path array");
        for(JsonValue p:path){number(p,"x");number(p,"y");}return u;
    }
    private static SandboxKnowledgeView knowledge(JsonValue sample) {
        return SandboxKnowledgeView.sample(sample);
    }
    private static boolean sameKnowledge(JsonValue a,JsonValue b) {
        SandboxKnowledgeView x=knowledge(a),y=knowledge(b);
        for(int i=0;i<600;i++){JsonValue p=x.get(i),q=y.get(i);
            if(bool(p,"visible")!=bool(q,"visible")||p.get("gid").isNull()!=q.get("gid").isNull())return false;
            if(!p.get("gid").isNull()&&(p.getLong("gid")!=q.getLong("gid")||p.getBoolean("blocked")!=q.getBoolean("blocked")))return false;
        }return true;
    }
    private static boolean active(JsonValue initial,JsonValue current,JsonValue target) {
        sampleIdentity(initial);JsonValue u=unit(current);
        require(string(initial,"worldId").equals(string(current,"worldId")),"Old world changed within its run");
        require(integer(current,"stepIndex")>=0&&number(current,"simulationTime")>0,"Invalid active time");
        JsonValue goal=field(u,"moveTarget");
        if(number(u,"speed")==0||goal.isNull()||field(u,"pathPoints").size==0)return false;
        require(goal.isObject()&&number(goal,"x")==number(target,"x")&&number(goal,"y")==number(target,"y"),"Original move goal changed");
        JsonValue pos=field(u,"position"),origin=field(unit(initial),"position");
        return number(pos,"x")!=number(origin,"x")||number(pos,"y")!=number(origin,"y");
    }
    public static int learned(JsonValue initial,JsonValue current,JsonValue target) {
        SandboxKnowledgeView a=knowledge(initial),b=knowledge(current);
        if(!active(initial,current,target))return -1;
        for(int i=0;i<600;i++)if(a.get(i).get("gid").isNull()&&bool(b.get(i),"visible")&&b.get(i).get("gid").isLong())return i;
        return -1;
    }
    private static void records(List<JsonValue> rows) {
        for(int i=0;i<rows.size();i++){
            JsonValue row=rows.get(i);require(integer(row,"sequence")==i,"Noncontiguous run sequence");String kind=string(row,"kind");
            require(!List.of("cleanup-error","load-error","launch-failed").contains(kind),"Application failure in recreation stream");
        }
    }
    private static int find(List<JsonValue> rows,String kind,String phase) {
        int at=-1;
        for(int i=0;i<rows.size();i++)if(string(rows.get(i),"kind").equals(kind)&&(phase==null||string(rows.get(i),"phase").equals(phase))){
            require(at<0,"Duplicate "+kind+"/"+phase);at=i;
        }return at;
    }
    public static boolean closed(List<JsonValue> rows,long generation,long boundary,boolean changing,JsonValue target) {
        return closure(rows,generation,boundary,changing,target,true);
    }
    public static boolean released(List<JsonValue> rows,long generation,long boundary,boolean changing) {
        return closure(rows,generation,boundary,changing,null,false);
    }
    private static boolean closure(List<JsonValue> rows,long generation,long boundary,boolean changing,JsonValue target,boolean activeRequired) {
        records(rows);require(generation>0&&boundary>=0,"Invalid closure identity/boundary");
        int[] chain={find(rows,"presentation-released",null),find(rows,"pause",null),find(rows,"observation","before-close"),
            find(rows,"closed",null),find(rows,"match-exit",null),find(rows,"application-disposed",null),
            find(rows,"activity-destroyed",null),find(rows,"evidence-completed",null)};
        int previous=-1;boolean missing=false;
        for(int at:chain){if(at<0){missing=true;continue;}require(!missing&&at>previous&&at>=boundary,"Closure chain is incomplete/reordered or precedes request");previous=at;}
        if(chain[6]>=0)require(bool(rows.get(chain[6]),"changingConfigurations")==changing,"Wrong Activity destruction cause");
        if(chain[7]>=0){require(string(rows.get(chain[7]),"status").equals("normal"),"Abnormal evidence completion");require(chain[7]==rows.size()-1,"Records appended after evidence completion");}
        for(JsonValue r:rows)if(r.has("generation"))require(integer(r,"generation")==generation,"Generation changed within run");
        if(changing&&activeRequired&&chain[2]>=0){
            int initial=find(rows,"observation","initial");require(initial>=0,"Missing old initial");
            JsonValue last=null;
            for(int i=0;i<chain[1];i++)if(string(rows.get(i),"kind").equals("observation")&&string(rows.get(i),"phase").equals("step"))last=field(rows.get(i),"sample");
            require(last!=null&&learned(field(rows.get(initial),"sample"),last,target)>=0,"Actual pause lost active/new-knowledge precondition");
            JsonValue closing=field(rows.get(chain[2]),"sample");
            require(learned(field(rows.get(initial),"sample"),closing,target)>=0&&integer(closing,"stepIndex")==integer(last,"stepIndex")
                &&number(closing,"simulationTime")==number(last,"simulationTime")&&sameKnowledge(last,closing),"Old world changed while paused or ceased to be active");
            JsonValue a=unit(last),b=unit(closing);
            for(String key:List.of("speed","angularVelocity","heading"))require(number(a,key)==number(b,key),"Paused old movement state changed");
            for(String key:List.of("x","y"))require(number(field(a,"position"),key)==number(field(b,"position"),key),"Paused old position changed");
        }
        if(chain[1]>=0)for(int i=chain[1]+1;i<rows.size();i++)if(string(rows.get(i),"kind").equals("observation"))
            require(i==chain[2],"Observation advanced after actual pause");
        return !missing;
    }
    public static boolean setup(List<JsonValue> rows) {
        records(rows);int launch=find(rows,"launch",null);
        if(launch>=0)require(bool(rows.get(launch),"restoredActivity"),"Replacement launch was not restored Activity");
        for(JsonValue r:rows)require(!List.of("load-request","load-ready","observation","action","selection").contains(string(r,"kind")),"New Activity auto-restored a match");
        return launch>=0&&rows.stream().anyMatch(r->string(r,"kind").equals("resize"));
    }
    public static int fresh(List<JsonValue> rows,JsonValue initial,String mode,long startBoundary,long stopBoundary,int learnedCell) {
        records(rows);require(startBoundary>=0&&startBoundary<=rows.size()&&stopBoundary>=startBoundary,"Invalid new input boundaries");
        require(setup(rows.subList(0,(int)startBoundary)),"New setup was not ready before Start");
        require(learnedCell>=0&&learnedCell<600&&knowledge(initial).get(learnedCell).get("gid").isNull(),"Bad old learned-cell identity");
        int request=find(rows,"load-request",null),ready=find(rows,"load-ready",null),first=find(rows,"observation","initial");
        require(request>=startBoundary,"Missing explicit new Start");long gen=integer(rows.get(request),"generation");require(gen>0,"Invalid new generation");
        SandboxFunctionalOracle.configuration(rows.get(request),mode,false);
        for(int i=(int)startBoundary;i<request;i++)require(!List.of("observation","action","selection").contains(string(rows.get(i),"kind")),"New match before Start request");
        if(first<0)return 0;
        require(first>request,"New initial precedes load request");JsonValue birth=field(rows.get(first),"sample");
        SandboxFunctionalOracle.initial(birth,mode,false);JsonValue original=unit(initial),born=unit(birth);
        require(string(born,"movementState").equals("idle"),"New initial retained movement state");
        require(sameKnowledge(initial,birth),"New match inherited old knowledge");
        require(string(original,"typeId").equals(string(born,"typeId"))&&number(original,"heading")==number(born,"heading"),"New unit definition/heading changed");
        int afterStop=0;long step=-1;double time=0;
        for(int i=0;i<rows.size();i++){
            JsonValue r=rows.get(i);String kind=string(r,"kind");
            require(!kind.equals("action")&&!kind.equals("selection"),"New match inherited selection or issued an action");
            if(r.has("generation"))require(integer(r,"generation")==gen,"New run generation changed");
            if(!kind.equals("observation"))continue;
            JsonValue s=field(r,"sample"),u=unit(s);require(string(s,"worldId").equals(string(birth,"worldId")),"New world changed within run");
            require(sameKnowledge(birth,s),"New knowledge changed without any movement");
            require(string(u,"typeId").equals(string(born,"typeId"))&&number(u,"heading")==number(born,"heading")
                &&number(u,"speed")==0&&number(u,"angularVelocity")==0&&field(u,"moveTarget").isNull()&&field(u,"pathPoints").size==0
                &&string(u,"movementState").equals("idle"),
                "Old motion/target/route leaked into new match");
            for(String key:List.of("x","y"))require(number(field(u,"position"),key)==number(field(born,"position"),key),"Old position leaked into new match");
            long next=integer(s,"stepIndex");double nextTime=number(s,"simulationTime");String phase=string(r,"phase");
            if(phase.equals("initial"))require(i==first&&next==0&&nextTime==0,"Duplicate or dirty initial");
            else if(phase.equals("step")){require(next>=0&&next>step&&next==step+1&&nextTime>time&&nextTime==time+1,"New time/step discontinuity");step=next;time=nextTime;if(i>=stopBoundary)afterStop++;}
            else require(phase.equals("before-close")&&next==step&&nextTime==time,"Unexpected new observation phase/time");
        }
        if(ready<0)return 0;
        require(ready>first&&integer(rows.get(ready),"generation")==gen,"Ready is not this new initial");return afterStop;
    }
    public static void combined(long oldBytes,long newBytes,int oldSteps,int newSteps,boolean closing) {
        long cap=(closing?16L:12L)*1024*1024;int steps=closing?500:479;
        require(oldBytes>=0&&newBytes>=0&&oldBytes<=cap&&newBytes<=cap&&oldBytes+newBytes<=cap
            &&oldSteps>=0&&newSteps>=0&&oldSteps<=steps&&newSteps<=steps&&(long)oldSteps+newSteps<=steps,"Combined recreation budget exceeded");
    }
}
