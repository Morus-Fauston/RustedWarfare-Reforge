package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonValue;
import java.util.ArrayList;
import java.util.List;

/** TEST-only validation of complete raw JSONL prefixes around a real resume frame. */
public final class SandboxPauseOracle {
    public static int verify(byte[] bytes, long generation, JsonValue target,
                             long offsetA, long offsetB, long frameA, long frameB, double delta) {
        try {
            return inspect(bytes,generation,target,offsetA,offsetB,frameA,frameB,delta);
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid pause evidence",failure); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static long integer(JsonValue row, String key) {
        require(row.has(key) && row.get(key).isLong(),"Missing integer " + key);
        return row.getLong(key);
    }
    private static double number(JsonValue row, String key) {
        require(row.has(key) && row.get(key).isNumber(),"Missing number " + key);
        double value=row.getDouble(key);
        require(Double.isFinite(value),"Nonfinite " + key);
        return value;
    }
    private static JsonValue unit(JsonValue sample, JsonValue target, String world) {
        require(sample.getString("worldId").equals(world) && sample.getString("playerId").equals("p1")
            && sample.getString("scenarioId").equals("p002-knowledge"),"Pause world/player/scenario changed");
        JsonValue found=null;
        require(sample.get("units").isArray(),"Missing units");
        for (JsonValue candidate : sample.get("units")) if (candidate.getString("unitId").equals("u1")) {
            require(found==null,"Duplicate u1"); found=candidate;
        }
        require(found!=null && found.getString("ownerPlayerId").equals("p1"),"Missing own u1");
        double x=number(found.get("position"),"x"),y=number(found.get("position"),"y");
        require(x>=11 && x<=269 && y>=11 && y<=389,"Position left the fixed safe movement rectangle");
        double speed=number(found,"speed"); number(found,"angularVelocity");
        require(speed>=0 && speed<=1.1,"Invalid fixed-scene speed");
        require(found.get("moveTarget").isObject()
            && number(found.get("moveTarget"),"x")==number(target,"x")
            && number(found.get("moveTarget"),"y")==number(target,"y"),"Original goal not retained");
        require(found.get("pathPoints").isArray() && found.get("pathPoints").size>0,"Active path lost");
        for (JsonValue point : found.get("pathPoints")) { number(point,"x"); number(point,"y"); }
        return found;
    }
    private static int inspect(byte[] bytes, long generation, JsonValue target,
                               long a,long b,long frameA,long frameB,double delta) {
        require(generation>0 && a>=0 && a<=b && b<=bytes.length,"Invalid pause offsets");
        require(frameA>=0 && frameA<Long.MAX_VALUE && frameB==frameA+1 && delta==0,"Invalid first resume frame");
        require(a==0 || bytes[(int)a-1]=='\n',"A is not a complete line boundary");
        require(b==0 || bytes[(int)b-1]=='\n',"B is not a complete line boundary");
        SandboxFunctionalOracle.Stream stream=new SandboxFunctionalOracle.Stream();
        stream.feed(bytes,0,true); stream.complete();
        List<Long> ends=new ArrayList<>();
        for (int i=0;i<bytes.length;i++) if (bytes[i]=='\n') ends.add((long)i+1);
        List<JsonValue> rows=stream.records;
        require(ends.size()==rows.size(),"Raw row boundaries differ");
        int pause=-1,resume=-1,release=-1,launches=0,requests=0,ready=0;
        String world=null;
        for (int i=0;i<rows.size();i++) {
            JsonValue row=rows.get(i);
            require(integer(row,"sequence")==i,"Noncontiguous diagnostic sequence");
            String kind=row.getString("kind");
            if (kind.equals("launch")) launches++;
            if (kind.equals("load-request")) { requests++; require(integer(row,"generation")==generation,"Wrong load generation"); }
            if (kind.equals("load-ready")) { ready++; require(integer(row,"generation")==generation,"Wrong ready generation"); }
            if (kind.equals("pause")) { require(pause<0,"Duplicate business pause"); pause=i; }
            if (kind.equals("resume")) { require(resume<0,"Duplicate business resume"); resume=i; }
            if (kind.equals("presentation-released")) { require(release<0,"Unexpected second business release"); release=i; }
            require(!List.of("cleanup-error","launch-failed","load-error","application-disposed","activity-destroyed",
                "match-exit","closed","evidence-completed").contains(kind),"Premature close/error: " + kind);
            if (kind.equals("observation")) {
                require(integer(row,"generation")==generation,"Observation generation changed");
                String currentWorld=row.get("sample").getString("worldId");
                if (world==null) world=currentWorld;
                require(world.equals(currentWorld),"Observation world changed");
            }
        }
        require(launches==1 && requests==1 && ready==1,"Expected one launch and load");
        require(world!=null && release>=0 && release<pause && pause<resume && ends.get(resume)<=a,
            "Missing ordered release/pause/resume before A");
        JsonValue previous=null;
        for (int i=0;i<pause;i++) {
            JsonValue row=rows.get(i);
            if (row.getString("kind").equals("observation") && row.getString("phase").equals("step")) previous=row.get("sample");
        }
        require(previous!=null,"No actual step before pause");
        JsonValue previousUnit=unit(previous,target,world);
        require(number(previousUnit,"speed")>0 && (number(previousUnit.get("position"),"x")!=110
            || number(previousUnit.get("position"),"y")!=110),"Pause happened before actual active movement");
        long lastStep=integer(previous,"stepIndex");
        double lastTime=number(previous,"simulationTime");
        require(lastStep>=0 && lastTime>=0,"Negative pause baseline step/time");
        int count=0; boolean moved=false;
        for (int i=pause+1;i<rows.size();i++) {
            JsonValue row=rows.get(i);
            String kind=row.getString("kind");
            require(!List.of("action","selection","load-request","load-ready","launch").contains(kind),
                "Unexpected business operation during pause exercise");
            if (!kind.equals("observation")) continue;
            require(ends.get(i)>b,"Simulation advanced while paused or in first resumed render");
            require(row.getString("phase").equals("step"),"Unexpected resumed observation phase");
            JsonValue sample=row.get("sample"), current=unit(sample,target,world);
            long step=integer(sample,"stepIndex"); double time=number(sample,"simulationTime");
            require(step>=0 && time>=0 && step>lastStep && time>lastTime
                && step==lastStep+1 && time==lastTime+1,"Resumed simulation time/step discontinuity");
            JsonValue before=previousUnit.get("position"), after=current.get("position");
            double x=number(after,"x"),y=number(after,"y"),px=number(before,"x"),py=number(before,"y");
            // Fixed speed 1.1 × delta 1. Allow only rounding in sin/cos, additions, subtraction and hypot.
            double scale=Math.max(1.1,Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.max(Math.abs(px),Math.abs(py))));
            double rounding=8*Math.ulp(scale);
            double distance=Math.hypot(x-px,y-py);
            require(distance<=1.1+rounding,"Resumed position jumped beyond one fixed tick");
            moved |= distance>0;
            previousUnit=current; lastStep=step; lastTime=time; count++;
        }
        require(count<3 || moved,"Resumed world did not continue moving");
        return count;
    }
}
