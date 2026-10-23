package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonValue;
import java.util.List;

/** TEST-only public-state/input evidence. Image/HUD and actual GL fence provenance are separate gates. */
public final class SandboxVisibilityOracle {
    private final long generation;
    private final String world;
    private final double pixelWidth, pixelHeight;
    private long sequence, step=-1, firstLoss=-1, hitMinimum=-1, pointerSequence=-1, processedFrom=-1;
    private double time, x=110, y=110, speed, approachX, approachY, leaveX, leaveY, lossX, lossY, frameX, frameY;
    private int actions, selections, pendingAction, postFrameSteps;
    private String pendingSelection;
    private JsonValue latest, terminal;
    private boolean failed, armed, beforeAction, closing, closed, hiddenMoved, frameMoved, leaveArrived;
    private boolean awaitingInitialReady=true;
    private SandboxNormalCompletionOracle approachCompletion,leaveCompletion;
    private String stoppedApproachUnit;
    private int windowWidth, windowHeight, screenX, screenY;
    private static final String[] TYPES={"SelectAction","MoveAction","SelectAction","SelectAction","MoveAction","SelectAction"};
    private static final String[] IDS={"u1","u1","u3","u1","u1","u3"};
    private static final String[] SELECTED={"u1","u3","u1","u3"};
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException("Visibility: "+message);}
    private static long integer(JsonValue row,String key){require(row!=null&&row.has(key)&&row.get(key).isLong(),"missing integer "+key);return row.getLong(key);}
    private static String string(JsonValue row,String key){require(row!=null&&row.has(key)&&row.get(key).isString(),"missing string "+key);return row.getString(key);}
    private static double number(JsonValue row,String key){require(row!=null&&row.has(key)&&row.get(key).isNumber()&&Double.isFinite(row.getDouble(key)),"invalid number "+key);return row.getDouble(key);}
    private JsonValue unit(JsonValue sample,String id){
        JsonValue units=sample.get("units"),found=null;require(units!=null&&units.isArray(),"missing units");
        for(JsonValue u:units)if(string(u,"unitId").equals(id)){require(found==null,"duplicate "+id);found=u;}
        return found;
    }
    private JsonValue own(JsonValue sample){
        require(string(sample,"worldId").equals(world)&&string(sample,"scenarioId").equals("p002-knowledge")
            &&string(sample,"playerId").equals("p1"),"observation identity changed");
        JsonValue u=unit(sample,"u1");require(u!=null&&string(u,"ownerPlayerId").equals("p1"),"missing own u1");
        number(u.get("position"),"x");number(u.get("position"),"y");double velocity=number(u,"speed");
        require(velocity>=0&&velocity<=1.1,"invalid own speed");
        require(u.has("moveTarget")&&(u.get("moveTarget").isNull()||u.get("moveTarget").isObject()),"invalid own goal");
        JsonValue path=u.get("pathPoints");require(path!=null&&path.isArray(),"missing path");
        for(JsonValue point:path){number(point,"x");number(point,"y");}
        JsonValue foreign=unit(sample,"u3");
        if(foreign!=null)require(string(foreign,"ownerPlayerId").equals("p3")&&number(foreign.get("position"),"x")==550
            &&number(foreign.get("position"),"y")==310&&number(foreign,"speed")==0,"foreign public identity/pose changed");
        return u;
    }
    private boolean visible(){return latest!=null&&unit(latest,"u3")!=null;}
    private boolean stopped(){
        JsonValue u=own(latest);return u.get("moveTarget").isNull()&&u.get("pathPoints").size==0&&number(u,"speed")==0;
    }
    public SandboxVisibilityOracle(JsonValue initial,String mode,double pixelWidth,double pixelHeight){
        require(List.of("MEMORY","HARDCORE").contains(mode)&&Double.isFinite(pixelWidth)&&Double.isFinite(pixelHeight)
            &&pixelWidth>0&&pixelHeight>0&&Math.hypot(pixelWidth,pixelHeight)<11,"invalid fixed visibility configuration");
        this.pixelWidth=pixelWidth;this.pixelHeight=pixelHeight;
        generation=integer(initial,"generation");sequence=integer(initial,"sequence");
        require(generation>0&&sequence>=0&&string(initial,"kind").equals("observation")&&string(initial,"phase").equals("initial"),"invalid initial identity");
        latest=initial.get("sample");world=string(latest,"worldId");require(!world.isEmpty(),"empty world");
        JsonValue u=own(latest);
        require(integer(latest,"stepIndex")==0&&number(latest,"simulationTime")==0&&!visible()&&stopped()
            &&number(u.get("position"),"x")==110&&number(u.get("position"),"y")==110,"not the original clean hidden initial state");
    }
    /** Caller must separately require the fixed false-sharing launch options and real baseline image. */
    public boolean approachReady(){return !failed&&actions==2&&pendingAction==0&&visible()&&stopped()&&approachCompletion!=null&&approachCompletion.stopped();}
    public boolean visibleSelected(){return !failed&&actions==3&&selections==2&&pendingAction==0&&pendingSelection==null&&visible()&&stopped()&&approachCompletion!=null&&approachCompletion.stopped();}
    public boolean hiddenReady(){return !failed&&!beforeAction&&!closing&&!closed&&actions==6&&selections==4
        &&firstLoss>=0&&step-firstLoss>=3&&hiddenMoved&&!visible()&&activeLeave();}
    private boolean activeLeave(){
        return leaveIntent()&&number(own(latest),"speed")>0;
    }
    private boolean leaveIntent(){
        JsonValue u=own(latest),goal=u.get("moveTarget");
        return goal.isObject()&&number(goal,"x")==leaveX&&number(goal,"y")==leaveY&&u.get("pathPoints").size>0;
    }
    public void armHiddenHit(long nextSequence,int width,int height,int localX,int localY){
        try{
            require(hiddenReady()&&!armed&&nextSequence==sequence+1&&sequence<Long.MAX_VALUE,"hidden hit needs a fresh complete prefix");
            require(width>0&&height>0&&localX>=0&&localX<width&&localY>=0&&localY<height,"invalid hidden hit window");
            armed=true;hitMinimum=nextSequence;windowWidth=width;windowHeight=height;screenX=localX;screenY=localY;
        }catch(RuntimeException failure){failed=true;throw failure;}
    }
    /** Bind a real completed post-pointer GL frame and its exact complete-prefix boundary before counting new steps. */
    public void confirmProcessedFrame(long nextSequence,long frameA,long frameB){
        try{
            require(!failed&&pointerSequence>=0&&processedFrom<0&&nextSequence==sequence+1&&sequence<Long.MAX_VALUE
                &&nextSequence>pointerSequence&&frameA>=0&&frameA<Long.MAX_VALUE&&frameB==frameA+1,"invalid post-pointer frame/prefix");
            require(hiddenReady(),"hidden route no longer active at processed frame");processedFrom=nextSequence;frameX=x;frameY=y;
        }catch(RuntimeException failure){failed=true;throw failure;}
    }
    public void accept(JsonValue row){
        try{
            long next=integer(row,"sequence");
            require(sequence<Long.MAX_VALUE&&next==sequence+1,"record missing/reordered/duplicated");sequence=next;
            String kind=string(row,"kind");
            require(!List.of("cleanup-error","load-error","launch-failed").contains(kind),"application "+kind);
            if(awaitingInitialReady){
                require(kind.equals("load-ready")&&integer(row,"generation")==generation
                    &&integer(row,"mapWidth")==30&&integer(row,"mapHeight")==20&&integer(row,"blockedCount")==33,
                    "initial missing adjacent fixed load-ready");
                awaitingInitialReady=false;return;
            }
            require(!List.of("load-request","load-ready","resize").contains(kind),"reload/viewport changed");
            require(!kind.equals("resume"),"unexpected resume");
            if(List.of("pause","match-exit","presentation-released","application-disposed").contains(kind))
                require(terminal!=null,"lifecycle ended before negative input qualification");
            if(row.has("generation"))require(integer(row,"generation")==generation,"generation changed");
            if(List.of("action","selection","pointer","observation","closed").contains(kind))require(integer(row,"generation")==generation,"missing generation");
            if(closed)require(!List.of("observation","action","selection","pointer","closed").contains(kind),"business record after closed");
            if(closing&&!closed)require(kind.equals("closed"),"before-close missing adjacent closed record");
            if(beforeAction)require(kind.equals("action"),"before-action missing adjacent action");
            if(pendingAction!=0)require(kind.equals("observation")&&string(row,"phase").equals("after-action"),"action missing adjacent observation");
            if(pendingSelection!=null)require(kind.equals("selection"),"selection missing adjacent receipt");
            switch(kind){
                case "action":action(row);break;
                case "selection":selection(row);break;
                case "pointer":pointer(row);break;
                case "observation":observation(row);break;
                case "closed":require(closing&&!closed&&terminal!=null,"closed before qualified before-close");closed=true;break;
                default:break;
            }
        }catch(RuntimeException failure){failed=true;throw failure;}
    }
    private void action(JsonValue row){
        require(beforeAction&&!closing&&!closed,"action missing adjacent before-action");beforeAction=false;
        require(actions<6&&string(row,"type").equals(TYPES[actions])&&string(row,"unitId").equals(IDS[actions])
            &&string(row,"actorPlayerId").equals("p1"),"unexpected action/order/identity");
        JsonValue result=row.get("result");require(result!=null&&result.has("accepted")&&result.get("accepted").isBoolean()&&result.getBoolean("accepted"),"rejected action");
        if(actions==0)require(selections==0,"initial selection order");
        if(actions==1)require(selections==1,"approach without own selection");
        if(actions==2)require(approachReady(),"u3 baseline selected before visible stopped approach");
        if(actions==3)require(visibleSelected(),"leave before baseline selection");
        if(actions==4)require(selections==3,"leave without own selection");
        // The approved movement model may turn in place before accelerating; the route must already exist.
        if(actions==5)require(selections==3&&visible()&&leaveIntent(),"second u3 select after visibility loss/missing leave intent");
        JsonValue target=row.get("target");require(target!=null,"missing target field");
        if(TYPES[actions].equals("MoveAction")){
            double tx=number(target,"x"),ty=number(target,"y"),intentX=actions==1?530:110,intentY=actions==1?270:290;
            require(Math.abs(tx-intentX)<=pixelWidth&&Math.abs(ty-intentY)<=pixelHeight,"move outside physical pixel intent");
            if(actions==4){leaveX=tx;leaveY=ty;}
            else{approachX=tx;approachY=ty;}
        }else require(target.isNull(),"select has target");
        pendingAction=++actions;
    }
    private void selection(JsonValue row){
        require(pendingSelection!=null&&selections<4&&string(row,"selectedId").equals(SELECTED[selections])
            &&string(row,"selectedId").equals(pendingSelection),"unexpected/manual selection clear");
        JsonValue selected=unit(latest,pendingSelection);require(selected!=null,"selected unit not visible");
        require(Math.hypot(number(row.get("world"),"x")-number(selected.get("position"),"x"),
            number(row.get("world"),"y")-number(selected.get("position"),"y"))<=11,"selection hit missed public unit");
        selections++;pendingSelection=null;
    }
    private void pointer(JsonValue row){
        // Earlier input receipts are followed by the exact six action/selection sequence above.
        if(selections<4)return;
        require(armed&&pointerSequence<0&&sequence>=hitMinimum&&hiddenReady(),"unexpected/unarmed/duplicate post-selection input");
        require(integer(row,"windowWidth")==windowWidth&&integer(row,"windowHeight")==windowHeight
            &&integer(row,"screenX")==screenX&&integer(row,"screenY")==screenY&&integer(row,"button")==0,"hidden pointer viewport/screen/button mismatch");
        double wx=number(row.get("world"),"x"),wy=number(row.get("world"),"y");
        require(Math.abs(wx-550)<=pixelWidth&&Math.abs(wy-310)<=pixelHeight&&Math.hypot(wx-550,wy-310)<11,"hidden pointer missed original u3");
        pointerSequence=sequence;
    }
    private void observation(JsonValue row){
        String phase=string(row,"phase");JsonValue sample=row.get("sample"),u=own(sample);
        long nextStep=integer(sample,"stepIndex");double nextTime=number(sample,"simulationTime");
        double nx=number(u.get("position"),"x"),ny=number(u.get("position"),"y"),ns=number(u,"speed");
        boolean tick=phase.equals("step");
        // Subtracting map coordinates loses precision at their magnitude, not at speed's magnitude.
        double rounding=8*(Math.ulp(x)+Math.ulp(y)+Math.ulp(nx)+Math.ulp(ny));
        if(tick)require(nextStep==step+1&&nextTime==time+1&&Math.hypot(nx-x,ny-y)<=1.1+rounding,"step/time/movement discontinuity");
        else require(List.of("before-action","after-action","before-close").contains(phase)&&nextStep==(step<0?0:step)
            &&nextTime==time&&nx==x&&ny==y&&ns==speed,"unexpected phase or advancement without step");
        boolean newMove=false;
        if(pendingAction!=0){
            if(TYPES[pendingAction-1].equals("MoveAction")){
                JsonValue goal=u.get("moveTarget");double tx=pendingAction==2?approachX:leaveX,ty=pendingAction==2?approachY:leaveY;
                require(goal.isObject()&&number(goal,"x")==tx&&number(goal,"y")==ty&&u.get("pathPoints").size>0,"move did not set adjacent goal/path");
                SandboxNormalCompletionOracle completion=new SandboxNormalCompletionOracle(goal,u);
                if(pendingAction==2)approachCompletion=completion;else leaveCompletion=completion;
                newMove=true;
            }else pendingSelection=IDS[pendingAction-1];
            pendingAction=0;
        }else require(!phase.equals("after-action"),"unpaired after-action observation");
        SandboxNormalCompletionOracle current=leaveCompletion!=null?leaveCompletion:approachCompletion;
        if(current!=null&&!newMove)current.observe(u,sample.get("events"),tick);
        if(leaveCompletion==null&&approachCompletion!=null&&approachCompletion.stopped()){
            String fixed=u.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json);
            if(stoppedApproachUnit==null)stoppedApproachUnit=fixed;
            else require(stoppedApproachUnit.equals(fixed),"stopped approach full unit drifted");
        }
        if(phase.equals("before-action")){
            require(!beforeAction&&actions<6&&terminal==null,"unexpected/duplicate action prelude");beforeAction=true;
        }
        if(phase.equals("before-close")){
            require(terminal!=null&&!closing&&!closed,"before-close before business qualification/duplicate close");closing=true;
        }
        latest=sample;
        if(tick){step=nextStep;time=nextTime;}
        if(selections==4){
            if(firstLoss<0&&!visible()){
                require(tick&&actions==6&&activeLeave(),"first loss did not occur on actual active-route step");firstLoss=step;lossX=nx;lossY=ny;
            }
            if(firstLoss>=0){
                require(!visible(),"u3 returned after first loss");
                if(tick&&step>firstLoss&&(nx!=lossX||ny!=lossY))hiddenMoved=true;
                if(terminal==null)require(activeLeave()&&step<=2499,"inactive/exhausted leave before negative input terminal");
                else{
                    JsonValue goal=u.get("moveTarget");
                    require((!leaveArrived&&goal.isObject()&&number(goal,"x")==leaveX&&number(goal,"y")==leaveY&&u.get("pathPoints").size>0)
                        ||(goal.isNull()&&u.get("pathPoints").size==0&&leaveCompletion!=null&&leaveCompletion.normalCleared()),"tail changed/stopped the original leave intent");
                    if(goal.isNull()){
                        require(ns<=speed&&(speed!=0||(ns==0&&nx==x&&ny==y)),"tail accelerated/moved after arrival standstill");
                        leaveArrived=true;
                    }
                }
                if(tick&&processedFrom>=0&&sequence>=processedFrom&&terminal==null){
                    frameMoved|=nx!=frameX||ny!=frameY;
                    if(++postFrameSteps==3){require(frameMoved,"no actual movement in post-frame qualification steps");terminal=row;}
                }
            }
        }
        x=nx;y=ny;speed=ns;
    }
    public boolean ready(){return !failed&&terminal!=null;}
    public JsonValue terminal(){require(ready(),"no qualified negative-input terminal");return terminal;}
    public long firstLossStep(){return firstLoss;}
    public boolean pointerReceived(){return !failed&&pointerSequence>=0;}
    /** Check EVERY record in the stable image interval, in addition to ordered business validation. */
    public void imageStable(JsonValue row,boolean hidden){
        String kind=string(row,"kind");
        require(!List.of("action","selection","pointer","resize","pause","resume","load-request","load-ready",
            "match-exit","presentation-released","application-disposed","activity-destroyed","closed",
            "cleanup-error","load-error","launch-failed").contains(kind),"input/lifecycle changed during image interval");
        if(!kind.equals("observation"))return;
        require(integer(row,"generation")==generation,"image generation changed");
        JsonValue sample=row.get("sample"),u=own(sample),foreign=unit(sample,"u3"),goal=u.get("moveTarget");
        if(hidden)require(foreign==null&&goal.isObject()&&number(goal,"x")==leaveX&&number(goal,"y")==leaveY
            &&u.get("pathPoints").size>0&&number(u,"speed")>0,"hidden image did not retain hidden active route");
        else require(foreign!=null&&goal.isNull()&&u.get("pathPoints").size==0&&number(u,"speed")==0
            &&approachCompletion!=null&&approachCompletion.stopped()&&stoppedApproachUnit!=null
            &&stoppedApproachUnit.equals(u.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)),
            "visible image baseline was not visible/stopped");
    }
    public long nextSequence(){require(sequence<Long.MAX_VALUE,"sequence overflow");return sequence+1;}
    /** Same full-batch/closing drain intended for the Android frontend; no early success short-circuit. */
    public int drain(SandboxFunctionalOracle.Stream stream,SandboxFunctionalOracle.Failures errors,int cursor){
        require(cursor>=0&&cursor<=stream.records.size(),"invalid drain cursor");
        while(cursor<stream.records.size()){
            JsonValue row=stream.records.get(cursor++);
            try{accept(row);if(ready()&&stream.terminalSequence()<0)stream.endBusinessAt(terminal(),generation);}
            catch(Throwable failure){errors.business(failure);try{stream.failBusinessAt(failure,row);}catch(Throwable boundaryFailure){if(boundaryFailure!=failure)errors.business(boundaryFailure);}}
        }
        return cursor;
    }
}
