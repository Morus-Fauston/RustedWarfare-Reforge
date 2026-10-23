package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Actual S03 business rows with explicitly constructed UI receipt/fence metadata. No image/Android claim. */
public final class SandboxVisibilityOracleTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static JsonValue json(String value){return new JsonReader().parse(value);}
    private static JsonValue copy(JsonValue value){return json(value.toJson(JsonWriter.OutputType.json));}
    private static List<JsonValue> rows(Path input)throws Exception{
        List<JsonValue> out=new ArrayList<>();
        for(String line:Files.readAllLines(input))out.add(json(line));
        // S03 business traces omit Android's ready receipt. Add its fixed, explicitly constructed
        // envelope in the actual initial -> load-ready order; do not omit that row from the oracle.
        out.add(1,json("{\"kind\":\"load-ready\",\"generation\":1,\"sequence\":1,\"mapWidth\":30,\"mapHeight\":20,\"blockedCount\":33}"));
        renumber(out);
        return out;
    }
    private static JsonValue pointer(long sequence){return json("{\"kind\":\"pointer\",\"generation\":1,\"sequence\":"+sequence
        +",\"screenX\":574,\"screenY\":190,\"windowWidth\":960,\"windowHeight\":640,\"world\":{\"x\":550,\"y\":310},\"button\":0}");}
    private static void rejected(String name,Runnable action){
        try{action.run();}catch(IllegalArgumentException expected){System.out.println("rejected "+name+": "+expected.getMessage());return;}
        throw new AssertionError("Accepted negative "+name);
    }
    private static SandboxVisibilityOracle parsePrefix(List<JsonValue> rows,String mode){
        SandboxVisibilityOracle checker=new SandboxVisibilityOracle(rows.get(0),mode,1,1);
        for(int i=1;i<rows.size();i++){
            JsonValue row=rows.get(i);
            if(row.getString("hostPhase","").equals("hidden-hit"))break;
            checker.accept(row);
        }
        return checker;
    }
    private static SandboxVisibilityOracle prefix(List<JsonValue> rows,String mode){
        var checker=parsePrefix(rows,mode);
        require(checker.hiddenReady(),"Actual fixed S03 prefix did not reach three subsequent hidden steps");
        require(!checker.ready(),"Hidden steps without processed negative input qualified");return checker;
    }
    private static void arm(SandboxVisibilityOracle checker,long next){checker.armHiddenHit(next,960,640,574,190);}
    private static void feedTail(SandboxVisibilityOracle checker,List<JsonValue> rows,long firstSequence){
        feedTail(checker,rows,firstSequence,true);
    }
    private static void feedTail(SandboxVisibilityOracle checker,List<JsonValue> rows,long firstSequence,boolean close){
        long sequence=firstSequence;
        for(JsonValue original:rows){
            if(!original.getString("hostPhase","").equals("hidden-hit")||(!close&&!original.getString("phase","").equals("step")))continue;
            JsonValue row=copy(original);row.get("sequence").set(sequence++,null);checker.accept(row);
        }
    }
    private static byte[] wire(List<JsonValue> rows){
        StringBuilder text=new StringBuilder();for(JsonValue row:rows)text.append(row.toJson(JsonWriter.OutputType.json)).append('\n');
        return text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static void orderedDrain(List<JsonValue> rows,String mode,long next){
        List<JsonValue> before=new ArrayList<>(),tail=new ArrayList<>();long sequence=next+1;
        for(JsonValue row:rows){
            if(row.getString("hostPhase","").equals("hidden-hit")){JsonValue v=copy(row);v.get("sequence").set(sequence++,null);tail.add(v);}
            else before.add(row);
        }
        for(String late:List.of("none","business-first","application-first")){
            var stream=SandboxFunctionalOracle.Stream.forLongTrace();var errors=new SandboxFunctionalOracle.Failures();
            stream.feed(wire(before),1000,false);var c=new SandboxVisibilityOracle(stream.records.get(0),mode,1,1);
            int cursor=c.drain(stream,errors,1);require(errors.primary==null&&c.hiddenReady(),"Ordered prefix failed: "+errors.primary);
            arm(c,next);stream.feed(wire(List.of(pointer(next))),1000,false);cursor=c.drain(stream,errors,cursor);
            c.confirmProcessedFrame(next+1,10,11);
            List<JsonValue> after=new ArrayList<>();for(JsonValue r:tail)after.add(copy(r));
            if(!late.equals("none")){
                JsonValue bad=copy(after.get(after.size()-2));bad.get("sample").get("units").get(0).get("moveTarget").get("x").set(120d,null);
                JsonValue app=json("{\"kind\":\"cleanup-error\",\"generation\":1,\"sequence\":0}");
                int close=after.size()-2;
                after.add(close,late.equals("application-first")?app:bad);after.add(close+1,late.equals("application-first")?bad:app);
                for(int i=0;i<after.size();i++)after.get(i).get("sequence").set(next+1+i,null);
            }
            stream.feed(wire(after),1000,false);cursor=c.drain(stream,errors,cursor);
            require(cursor==stream.records.size(),"Ordered drain skipped closing rows");
            if(late.equals("none")){
                stream.checkAwait(true,1000);stream.complete();
                require(errors.primary==null&&c.ready()&&stream.terminalSequence()==c.terminal().getLong("sequence"),"Ordered terminal was not actually captured");
            }else{
                require(!c.ready()&&errors.primary!=null&&!errors.secondary.isEmpty(),"Late combined errors lost fail latch/secondary");
                require(errors.primary.getMessage().startsWith(late.equals("application-first")?"Visibility: application cleanup-error":"Normal completion: goal cleared/replaced"),"Earlier actual row error was replaced");
            }
            System.out.println(mode+" orderedDrain="+late+" rows="+cursor+" terminal="+stream.terminalSequence());
        }
    }
    private static List<JsonValue> delayHiddenHit(List<JsonValue> rows,int terminalStep){
        // Deliberately constructed budget fixture: repeat a hidden public state and renumber step/time.
        // This is not a claim that the S03 simulation actually held that pose for 2000 more ticks.
        List<JsonValue> result=new ArrayList<>();JsonValue previous=null;int delay=-1;
        for(JsonValue source:rows){
            if(delay<0&&source.getString("hostPhase","").equals("hidden-hit")){
                delay=terminalStep-(int)source.get("sample").getLong("stepIndex")-2;
                require(previous!=null&&delay>=0,"Bad delayed-hit template");
                for(int i=1;i<=delay;i++){
                    JsonValue repeat=copy(previous);JsonValue s=repeat.get("sample");
                    s.get("stepIndex").set(s.getLong("stepIndex")+i,null);s.get("simulationTime").set(s.getDouble("simulationTime")+i,null);result.add(repeat);
                }
            }
            JsonValue r=copy(source);
            if(delay>=0&&r.getString("kind").equals("observation")){
                JsonValue s=r.get("sample");s.get("stepIndex").set(s.getLong("stepIndex")+delay,null);s.get("simulationTime").set(s.getDouble("simulationTime")+delay,null);
            }
            result.add(r);
            if(source.getString("kind").equals("observation")&&source.getString("phase").equals("step"))previous=source;
        }
        for(int i=0;i<result.size();i++)result.get(i).get("sequence").set((long)i,null);
        return result;
    }
    private static JsonValue lastPrefixStep(List<JsonValue> rows){
        JsonValue last=null;for(JsonValue r:rows){if(r.getString("hostPhase","").equals("hidden-hit"))break;
            if(r.getString("kind").equals("observation")&&r.getString("phase").equals("step"))last=r;}
        return Objects.requireNonNull(last);
    }
    private static void renumber(List<JsonValue> rows){for(int i=0;i<rows.size();i++)rows.get(i).get("sequence").set((long)i,null);}
    private static SandboxVisibilityOracle qualified(List<JsonValue> rows,String mode,boolean close){
        var c=prefix(rows,mode);long next=c.nextSequence();arm(c,next);c.accept(pointer(next));c.confirmProcessedFrame(next+1,10,11);
        for(JsonValue source:rows)if(source.getString("hostPhase","").equals("hidden-hit")&&(close||source.getString("phase","").equals("step"))){
            JsonValue r=copy(source);r.get("sequence").set(c.nextSequence(),null);c.accept(r);
        }
        require(c.ready(),"Qualification fixture failed");return c;
    }
    private static void reviewBefore(List<JsonValue> rows,String mode){
        rejected(mode+" review-missing-before-action",()->{
            List<JsonValue> changed=new ArrayList<>();boolean removed=false;
            for(JsonValue r:rows){if(!removed&&r.getString("phase","").equals("before-action")){removed=true;continue;}changed.add(copy(r));}
            renumber(changed);prefix(changed,mode);
        });
        rejected(mode+" review-duplicate-before-action",()->{
            List<JsonValue> changed=new ArrayList<>();boolean inserted=false;
            for(JsonValue r:rows){changed.add(copy(r));if(!inserted&&r.getString("phase","").equals("before-action")){changed.add(copy(r));inserted=true;}}
            renumber(changed);prefix(changed,mode);
        });
        for(String phase:List.of("before-action","before-close","closed"))rejected(mode+" review-premature-"+phase,()->{
            var c=prefix(rows,mode);long next=c.nextSequence();JsonValue r=copy(lastPrefixStep(rows));r.get("sequence").set(next,null);
            if(phase.equals("closed")){r.get("kind").set("closed");r.remove("phase");r.remove("sample");}else r.get("phase").set(phase);
            c.accept(r);arm(c,next+1);c.accept(pointer(next+1));c.confirmProcessedFrame(next+2,10,11);feedTail(c,rows,next+2);
            require(c.ready(),"False qualification fixture did not reach terminal");
        });
        rejected(mode+" review-step-after-closed",()->{
            var c=qualified(rows,mode,true);JsonValue r=copy(c.terminal());r.get("sequence").set(c.nextSequence(),null);
            r.get("sample").get("stepIndex").set(r.get("sample").getLong("stepIndex")+1,null);
            r.get("sample").get("simulationTime").set(r.get("sample").getDouble("simulationTime")+1,null);c.accept(r);
        });
    }
    private static void reviewReady(List<JsonValue> rows,String mode){
        var valid=new SandboxVisibilityOracle(rows.get(0),mode,1,1);
        valid.accept(rows.get(1));
        for(String fault:List.of("missing","duplicate","wrong-generation","wrong-width","wrong-height","wrong-blocked","resize-first"))
            rejected(mode+" initial-ready-"+fault,()->{
                List<JsonValue> changed=new ArrayList<>();for(JsonValue row:rows)changed.add(copy(row));
                JsonValue ready=changed.get(1);
                switch(fault){
                    case "missing":changed.remove(1);break;
                    case "duplicate":changed.add(2,copy(ready));break;
                    case "wrong-generation":ready.get("generation").set(2L,null);break;
                    case "wrong-width":ready.get("mapWidth").set(31L,null);break;
                    case "wrong-height":ready.get("mapHeight").set(21L,null);break;
                    case "wrong-blocked":ready.get("blockedCount").set(32L,null);break;
                    case "resize-first":changed.add(1,json("{\"kind\":\"resize\",\"sequence\":0}"));break;
                    default:throw new AssertionError(fault);
                }
                renumber(changed);prefix(changed,mode);
            });
    }
    private static void reviewMotion(List<JsonValue> rows,String mode){
        for(boolean onlyPostFrame: new boolean[]{false,true})rejected(mode+" review-frozen-positive-speed-"+(onlyPostFrame?"post-frame":"hidden-window"),()->{
            long loss=prefix(rows,mode).firstLossStep();JsonValue origin=onlyPostFrame?lastPrefixStep(rows):rows.stream().filter(r->r.getString("phase","").equals("step")&&r.get("sample").getLong("stepIndex")==loss).findFirst().orElseThrow();
            JsonValue position=origin.get("sample").get("units").get(0).get("position");List<JsonValue> changed=new ArrayList<>();
            for(JsonValue source:rows){JsonValue r=copy(source);
                if(r.getString("kind").equals("observation")&&(onlyPostFrame?r.getString("hostPhase","").equals("hidden-hit"):r.get("sample").getLong("stepIndex")>=loss)){
                    JsonValue u=r.get("sample").get("units").get(0);u.remove("position");u.addChild("position",copy(position));
                }changed.add(r);
            }
            var c=parsePrefix(changed,mode);long next=c.nextSequence();arm(c,next);c.accept(pointer(next));c.confirmProcessedFrame(next+1,10,11);feedTail(c,changed,next+1);
            require(c.ready(),"Frozen false-positive fixture failed unexpectedly");
        });
    }
    private static JsonValue bridgeArrival(SandboxVisibilityOracle c){
        // Constructed lifecycle fixture only, not a real physical S03 trajectory.
        // Active bridge positions have bounded displacement. Normal clear and every brake step
        // use an explicit event and actual literal .187 progression, preserving heading/cg.
        JsonValue last=copy(c.terminal()),u=last.get("sample").get("units").get(0),pos=u.get("position");
        double tx=u.get("moveTarget").getDouble("x"),ty=u.get("moveTarget").getDouble("y");
        double x=pos.getDouble("x"),y=pos.getDouble("y"),distance=Math.hypot(x-tx,y-ty);
        require(distance>6.99,"Bridge prefix too near goal");int count=(int)Math.ceil(distance-6.99);
        double dx=(tx-x)/distance,dy=(ty-y)/distance,heading=(Math.toDegrees(Math.atan2(dx,-dy))+360)%360;
        long step=last.get("sample").getLong("stepIndex");double time=last.get("sample").getDouble("simulationTime");
        for(int i=1;i<=count;i++){
            JsonValue r=copy(last),s=r.get("sample"),unit=s.get("units").get(0);double f=(double)i/count;
            r.get("sequence").set(c.nextSequence(),null);s.get("stepIndex").set(step+i,null);s.get("simulationTime").set(time+i,null);
            unit.get("position").get("x").set(x+dx*(distance-6.99)*f,null);unit.get("position").get("y").set(y+dy*(distance-6.99)*f,null);
            unit.get("speed").set(1d,null);unit.get("heading").set(heading,null);unit.get("angularVelocity").set(2.25,null);
            s.remove("events");s.addChild("events",json("[]"));
            c.accept(r);if(i==count)last=r;
        }
        double velocity=1;
        for(int i=0;i<6;i++){
            JsonValue r=copy(last),s=r.get("sample"),unit=s.get("units").get(0);
            r.get("sequence").set(c.nextSequence(),null);s.get("stepIndex").set(s.getLong("stepIndex")+1,null);s.get("simulationTime").set(s.getDouble("simulationTime")+1,null);
            velocity=Math.max(0,velocity-.187);unit.get("speed").set(velocity,null);unit.get("movementState").set(velocity==0?"idle":"decelerating");
            unit.get("position").get("x").set(unit.get("position").getDouble("x")+dx*velocity,null);
            unit.get("position").get("y").set(unit.get("position").getDouble("y")+dy*velocity,null);
            unit.remove("moveTarget");unit.addChild("moveTarget",json("null"));unit.remove("pathPoints");unit.addChild("pathPoints",json("[]"));
            s.remove("events");s.addChild("events",json(i==0?"[{kind:movement-arrived,unitId:u1}]":"[]"));
            c.accept(r);last=r;
        }
        require(Math.hypot(last.get("sample").get("units").get(0).get("position").getDouble("x")-tx,
            last.get("sample").get("units").get(0).get("position").getDouble("y")-ty)>4,"Constructed legal stop must exercise replaced4 gate");
        return last;
    }
    private static void reviewTail(List<JsonValue> rows,String mode){
        for(boolean restored: new boolean[]{true,false})rejected(mode+" review-after-arrival-"+(restored?"restored-route":"idle-motion"),()->{
            var c=qualified(rows,mode,false);JsonValue r=bridgeArrival(c),s=r.get("sample"),u=s.get("units").get(0);
            require(c.ready(),"Valid constructed arrival tail failed");
            r.get("sequence").set(c.nextSequence(),null);s.get("stepIndex").set(s.getLong("stepIndex")+1,null);s.get("simulationTime").set(s.getDouble("simulationTime")+1,null);
            if(restored){u.remove("moveTarget");u.addChild("moveTarget",json("{\"x\":110,\"y\":290}"));u.remove("pathPoints");u.addChild("pathPoints",json("[{\"x\":110,\"y\":290}]"));u.get("speed").set(1d,null);}
            else u.get("position").get("x").set(u.get("position").getDouble("x")+.5,null);
            c.accept(r);
        });
    }
    public static void main(String[] args)throws Exception{
        if(args.length<1||args.length>2)throw new IllegalArgumentException("Frozen actual S03 route directory and optional review group required");
        for(String mode:List.of("MEMORY","HARDCORE")){
            List<JsonValue> rows=rows(Path.of(args[0],mode+"-visibility-route-host.jsonl"));
            String group=args.length==2?args[1]:"all";
            if(group.equals("all"))reviewReady(rows,mode);
            if(group.equals("all")||group.equals("before"))reviewBefore(rows,mode);
            if(group.equals("all")||group.equals("motion"))reviewMotion(rows,mode);
            if(group.equals("all")||group.equals("tail"))reviewTail(rows,mode);
            if(!group.equals("all"))continue;
            JsonValue first=rows.stream().filter(r->r.getString("hostPhase","").equals("hidden-hit")).findFirst().orElseThrow();
            long next=first.getLong("sequence");
            orderedDrain(rows,mode,next);
            for(int at: new int[]{2499,2500}){
                List<JsonValue> delayed=delayHiddenHit(rows,at);var c=prefix(delayed,mode);
                long hit=delayed.stream().filter(r->r.getString("hostPhase","").equals("hidden-hit")).findFirst().orElseThrow().getLong("sequence");
                arm(c,hit);c.accept(pointer(hit));c.confirmProcessedFrame(hit+1,10,11);
                if(at==2499){feedTail(c,delayed,hit+1);require(c.ready()&&c.terminal().get("sample").getLong("stepIndex")==2499,"Last eligible third step failed");}
                else{rejected(mode+" third-post-frame-step-2500",()->feedTail(c,delayed,hit+1));require(!c.ready(),"Late terminal qualified");}
            }
            var good=prefix(rows,mode);arm(good,next);good.accept(pointer(next));good.confirmProcessedFrame(next+1,10,11);
            good.imageStable(first,true);
            JsonValue baseline=rows.stream().filter(r->r.getString("kind").equals("observation")&&
                r.get("sample").get("units").size>1&&r.get("sample").get("units").get(0).get("moveTarget").isNull()&&
                r.get("sample").get("units").get(0).getDouble("speed")==0&&r.getString("hostPhase","").equals("visible-baseline")).findFirst().orElseThrow();
            good.imageStable(baseline,false);
            good.imageStable(baseline,false); // Pure image validation must not advance a Move or event timer.
            for(String defect:List.of("anchor","missing-u3","generation","world","speed"))rejected(mode+" visible-image-"+defect,()->{
                JsonValue changed=copy(baseline),sample=changed.get("sample"),own=sample.get("units").get(0);
                switch(defect){
                    case "anchor":own.get("position").get("x").set(own.get("position").getDouble("x")+.01,null);break;
                    case "missing-u3":for(JsonValue value=sample.get("units").child;value!=null;value=value.next)if(value.getString("unitId").equals("u3")){value.remove();break;}break;
                    case "generation":changed.get("generation").set(2L,null);break;
                    case "world":sample.get("worldId").set("other");break;
                    case "speed":own.get("speed").set(.1,null);break;
                }
                good.imageStable(changed,false);
            });
            rejected(mode+" visible-image-hidden",()->good.imageStable(first,false));
            rejected(mode+" hidden-image-visible",()->good.imageStable(baseline,true));
            rejected(mode+" image-input",()->good.imageStable(pointer(next),true));
            JsonValue imageStopped=copy(first);
            imageStopped.get("sample").get("units").get(0).get("speed").set(0d,null);
            rejected(mode+" hidden-image-stopped",()->good.imageStable(imageStopped,true));
            JsonValue imageWorld=copy(first);imageWorld.get("sample").get("worldId").set("other");
            rejected(mode+" image-world",()->good.imageStable(imageWorld,true));
            feedTail(good,rows,next+1);require(good.ready(),"Actual business route with constructed pointer/fence failed");
            require(good.terminal().get("sample").getLong("stepIndex")==first.get("sample").getLong("stepIndex")+2,"Terminal was not third post-fence actual step");
            System.out.println(mode+" actualBusiness=true constructedPointerAndFrame=true images=false terminal="+good.terminal().get("sample").getLong("stepIndex"));

            var missing=prefix(rows,mode);feedTail(missing,rows,next,false);require(!missing.ready(),"No-pointer tail qualified");
            var noFrame=prefix(rows,mode);arm(noFrame,next);noFrame.accept(pointer(next));feedTail(noFrame,rows,next+1,false);
            require(!noFrame.ready(),"Pointer without completed frame qualified");
            rejected(mode+" frame-before-pointer",()->{var c=prefix(rows,mode);arm(c,next);c.confirmProcessedFrame(next,10,11);});
            rejected(mode+" nonadjacent-frame",()->{var c=prefix(rows,mode);arm(c,next);c.accept(pointer(next));c.confirmProcessedFrame(next+1,10,10);});
            rejected(mode+" stale-prefix-frame",()->{var c=prefix(rows,mode);arm(c,next);c.accept(pointer(next));c.confirmProcessedFrame(next,10,11);});
            rejected(mode+" double-frame",()->{var c=prefix(rows,mode);arm(c,next);c.accept(pointer(next));c.confirmProcessedFrame(next+1,10,11);c.confirmProcessedFrame(next+1,11,12);});
            rejected(mode+" pointer-unarmed",()->prefix(rows,mode).accept(pointer(next)));
            rejected(mode+" stale-arm-prefix",()->arm(prefix(rows,mode),next-1));
            rejected(mode+" duplicate-pointer",()->{var c=prefix(rows,mode);arm(c,next);c.accept(pointer(next));c.accept(pointer(next+1));});
            for(String kind:List.of("pause","resume","match-exit","resize","presentation-released","application-disposed","load-ready"))
                rejected(mode+" premature-"+kind,()->prefix(rows,mode).accept(json("{\"kind\":\""+kind+"\",\"sequence\":"+next+",\"generation\":1}")));
            Map<String,Consumer<JsonValue>> badPointers=new LinkedHashMap<>();
            badPointers.put("generation",r->r.get("generation").set(2L,null));
            badPointers.put("window",r->r.get("windowWidth").set(961L,null));
            badPointers.put("screen",r->r.get("screenX").set(576L,null));
            badPointers.put("world",r->r.get("world").get("x").set(560d,null));
            badPointers.put("button",r->r.get("button").set(1L,null));
            for(var bad:badPointers.entrySet())rejected(mode+" pointer-"+bad.getKey(),()->{
                var c=prefix(rows,mode);arm(c,next);JsonValue p=pointer(next);bad.getValue().accept(p);c.accept(p);require(!c.ready(),"Failure not latched");
            });
            rejected(mode+" hidden-selection",()->{var c=prefix(rows,mode);JsonValue r=json("{\"kind\":\"selection\",\"sequence\":"+next+",\"generation\":1,\"selectedId\":null}");c.accept(r);});
            rejected(mode+" hidden-rejected-move",()->{var c=prefix(rows,mode);JsonValue r=copy(rows.stream().filter(v->v.getString("kind").equals("action")).findFirst().orElseThrow());
                r.get("sequence").set(next,null);r.get("type").set("MoveAction");r.get("result").get("accepted").set(false);c.accept(r);});
            JsonValue hiddenStep=copy(first);
            Map<String,Consumer<JsonValue>> badSteps=new LinkedHashMap<>();
            badSteps.put("generation",r->r.get("generation").set(2L,null));
            badSteps.put("time",r->r.get("sample").get("simulationTime").set(999d,null));
            badSteps.put("step",r->r.get("sample").get("stepIndex").set(999L,null));
            badSteps.put("world",r->r.get("sample").get("worldId").set("other"));
            badSteps.put("goal",r->r.get("sample").get("units").get(0).get("moveTarget").get("x").set(120d,null));
            badSteps.put("path",r->{JsonValue u=r.get("sample").get("units").get(0);u.remove("pathPoints");u.addChild("pathPoints",json("[]"));});
            badSteps.put("visible-return",r->r.get("sample").get("units").addChild(json("{\"unitId\":\"u3\",\"ownerPlayerId\":\"p3\",\"position\":{\"x\":550,\"y\":310},\"speed\":0}")));
            for(var bad:badSteps.entrySet())rejected(mode+" hidden-step-"+bad.getKey(),()->{
                var c=prefix(rows,mode);JsonValue r=copy(hiddenStep);bad.getValue().accept(r);c.accept(r);
            });
            // A late failure must invalidate an already qualified business terminal.
            JsonValue late=json("{\"kind\":\"cleanup-error\",\"sequence\":"+(next+6)+",\"generation\":1}");
            rejected(mode+" late-application",()->good.accept(late));require(!good.ready(),"Late application error did not invalidate ready");
            rejected(mode+" no-visibility-before-second-select",()->{
                List<JsonValue> changed=new ArrayList<>();for(JsonValue r:rows)changed.add(copy(r));
                JsonValue select=changed.stream().filter(r->r.getString("kind").equals("action")&&r.getString("unitId","").equals("u3")).skip(1).findFirst().orElseThrow();
                long before=select.getLong("sequence")-1;
                JsonValue sample=changed.stream().filter(r->r.getLong("sequence")==before).findFirst().orElseThrow().get("sample");
                JsonValue units=sample.get("units");for(JsonValue u=units.child;u!=null;u=u.next)if(u.getString("unitId").equals("u3")){u.remove();break;}
                prefix(changed,mode);
            });
            rejected(mode+" adjacent-approach-goal-not-the-command",()->{
                List<JsonValue> changed=new ArrayList<>();for(JsonValue r:rows)changed.add(copy(r));
                long move=changed.stream().filter(r->r.getString("kind").equals("action")&&r.getString("type").equals("MoveAction")).findFirst().orElseThrow().getLong("sequence");
                JsonValue adjacent=changed.stream().filter(r->r.getLong("sequence")==move+1).findFirst().orElseThrow();
                adjacent.get("sample").get("units").get(0).get("moveTarget").get("x").set(530.5d,null);
                prefix(changed,mode);
            });
        }
        JsonValue initial=rows(Path.of(args[0],"MEMORY-visibility-route-host.jsonl")).get(0);
        rejected("NO_FOG not loss scenario",()->new SandboxVisibilityOracle(initial,"NO_FOG",1,1));
        rejected("nonfinite footprint",()->new SandboxVisibilityOracle(initial,"MEMORY",Double.NaN,1));
        rejected("footprint larger than hit radius",()->new SandboxVisibilityOracle(initial,"MEMORY",11,11));
        System.out.println("Visibility business oracle boundaries passed; all pointer/frame metadata above is constructed; no PNG or Android result.");
    }
}
