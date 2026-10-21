package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonWriter;
import com.rustedwarfare.reforge.client.*;
import com.rustedwarfare.reforge.core.sim.Position;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Fixed S03 session execution plus clearly separated constructed late-tail/limit negatives. */
public final class SandboxExploreOracleTest {
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid SC07 evidence accepted");
    }
    private static JsonValue copy(JsonValue row) { return new JsonReader().parse(row.toJson(JsonWriter.OutputType.json)); }
    private static SandboxExploreOracle checker(List<JsonValue> rows, String mode) {
        JsonValue move = rows.stream().filter(r->r.getString("kind").equals("action") && r.getString("type").equals("MoveAction")).findFirst().orElseThrow();
        JsonValue commanded = rows.stream().filter(r->r.getLong("sequence")==move.getLong("sequence")+1).findFirst().orElseThrow();
        return new SandboxExploreOracle(move,commanded,mode,16.0/27);
    }
    private static List<JsonValue> postCommandRows(List<JsonValue> rows) {
        JsonValue move=rows.stream().filter(r->r.getString("kind").equals("action")&&r.getString("type").equals("MoveAction")).findFirst().orElseThrow();
        for(int i=0;i<rows.size();i++)if(rows.get(i).getLong("sequence")==move.getLong("sequence")+1)return rows.subList(i+1,rows.size());
        throw new AssertionError("Missing actual command boundary");
    }
    private static SandboxExploreOracle replay(List<JsonValue> rows,String mode) {
        SandboxExploreOracle check = checker(rows,mode);
        for(JsonValue row:postCommandRows(rows))check.accept(row);
        require(check.ready(),"Actual SC07 did not become ready");return check;
    }
    private static void hiddenWall(JsonValue sample) {
        JsonValue cells=new JsonValue(JsonValue.ValueType.array);int index=0;
        for(JsonValue cell:SandboxKnowledgeView.sample(sample)) {
            JsonValue value=copy(cell);if(index++==164)value.get("visible").set(false);cells.addChild(value);
        }
        sample.remove("knowledge");sample.addChild("knowledge",cells);
    }
    private static List<JsonValue> stretchArrival(List<JsonValue> rows, int atStep) {
        List<JsonValue> result = new ArrayList<>();JsonValue arrived=null,previous=null,tail=null,close=null;
        for(JsonValue row:rows) {
            if(row.getString("kind").equals("observation")&&row.getString("phase").equals("step")) {
                if(arrived!=null){if(tail==null)tail=row;continue;}
                boolean isArrival=false;
                for(JsonValue event:row.get("sample").get("events")) if(event.getString("kind").equals("movement-arrived"))isArrival=true;
                if(isArrival){arrived=row;continue;}previous=row;
            }
            if(arrived==null)result.add(copy(row));
            if(row.getString("kind").equals("observation")&&row.getString("phase").equals("before-close"))close=row;
        }
        require(arrived!=null&&previous!=null&&tail!=null&&close!=null,"Missing real arrival template");
        for(int step=(int)arrived.get("sample").getLong("stepIndex");step<atStep;step++) {
            JsonValue delayed=copy(previous);delayed.get("sample").get("stepIndex").set((long)step,null);
            delayed.get("sample").get("simulationTime").set((long)step+1,null);result.add(delayed);
        }
        for(JsonValue template:new JsonValue[]{arrived,tail,close}) {
            JsonValue value=copy(template==close?tail:template);int step=template==arrived?atStep:atStep+1;
            if(template==close)value.get("phase").set("before-close");
            value.get("sample").get("stepIndex").set((long)step,null);value.get("sample").get("simulationTime").set((long)step+1,null);result.add(value);
        }
        for(int i=0;i<result.size();i++)result.get(i).get("sequence").set((long)i,null);
        return result;
    }
    private static void negatives(List<JsonValue> actual) {
        for(String kind:List.of("load-error","cleanup-error","launch-failed"))for(boolean first:new boolean[]{true,false}) {
            List<JsonValue> both=new ArrayList<>();for(JsonValue row:actual)both.add(copy(row));int badIndex=-1;
            for(int i=0;i<both.size();i++)if(both.get(i).getString("kind").equals("observation")&&both.get(i).getString("phase").equals("step"))badIndex=i;
            JsonValue unit=SandboxFunctionalOracle.unit(both.get(badIndex).get("sample"),"u1");
            unit.remove("moveTarget");unit.addChild("moveTarget",new JsonReader().parse("{x:350,y:110}"));
            both.add(first?badIndex:badIndex+1,new JsonReader().parse("{kind:"+kind+",sequence:0}"));
            for(int i=0;i<both.size();i++)both.get(i).get("sequence").set((long)i,null);
            var stream=SandboxFunctionalOracle.Stream.forLongTrace();StringBuilder wire=new StringBuilder();
            for(JsonValue row:both)wire.append(row.toJson(JsonWriter.OutputType.json)).append('\n');
            stream.feed(wire.toString().getBytes(StandardCharsets.UTF_8),1000,false);
            var checker=checker(stream.records,"MEMORY");var errors=new SandboxFunctionalOracle.Failures();
            int cursor=stream.records.indexOf(postCommandRows(stream.records).get(0));
            cursor=checker.drain(stream,errors,cursor);stream.applicationErrors(errors);
            require(cursor==stream.records.size()&&!checker.ready(),"Combined fault did not drain/fail complete batch");
            require(errors.primary!=null&&errors.primary.getMessage().startsWith(first?"Application "+kind:"SC07 goal/path restored"),
                "Application/business same-batch ordering replaced the earlier row cause");
            require(!errors.secondary.isEmpty(),"Later distinct business/application error was lost");
        }
        for(boolean after:new boolean[]{true,false})for(String kind:List.of("action","observation")) {
            List<JsonValue> low=new ArrayList<>();for(JsonValue row:actual)low.add(copy(row));
            int insert=after?low.size()-2:9; // Physically after command, irrespective of the forged sequence value.
            JsonValue injected=kind.equals("action")?new JsonReader().parse("{kind:action,type:MoveAction,generation:1,sequence:0}")
                :copy(low.stream().filter(r->r.getString("kind").equals("observation")&&r.getString("phase").equals("step")).findFirst().orElseThrow());
            injected.get("sequence").set(0L,null);low.add(insert,injected);
            SandboxExploreOracle lowCheck=checker(low,"MEMORY");
            rejected(()->{for(JsonValue row:postCommandRows(low))lowCheck.accept(row);});
            require(!lowCheck.ready(),"Low sequence "+kind+" was ignored "+(after?"after":"before")+" arrival");
        }
        List<JsonValue> end=stretchArrival(actual,2499);
        var stream=SandboxFunctionalOracle.Stream.forLongTrace();
        StringBuilder wire=new StringBuilder();for(JsonValue row:end)wire.append(row.toJson(JsonWriter.OutputType.json)).append('\n');
        stream.feed(wire.toString().getBytes(StandardCharsets.UTF_8),1000,false);
        var check=replay(stream.records,"MEMORY");stream.endBusinessAt(check.terminal(),1);stream.checkAwait(true,1000);stream.complete();
        require(check.terminal().get("sample").getLong("stepIndex")==2499&&stream.steps==2501,"Last eligible first arrival plus same-chunk tail failed");
        List<JsonValue> late=stretchArrival(actual,2500);SandboxExploreOracle lateCheck=checker(late,"MEMORY");
        rejected(()->{for(JsonValue row:postCommandRows(late))lateCheck.accept(row);});require(!lateCheck.ready(),"Late arrival turned green");
        for(String missing:List.of("discovery","reroute")) {
            SandboxExploreOracle missingCheck=checker(actual,"MEMORY");
            rejected(()->{for(JsonValue source:postCommandRows(actual)) {
                JsonValue row=copy(source);
                if(row.getString("kind").equals("observation")&&row.getString("phase").equals("step")) {
                    JsonValue sample=row.get("sample");
                    if(missing.equals("discovery"))hiddenWall(sample);
                    else {
                        JsonValue unit=SandboxFunctionalOracle.unit(sample,"u1");unit.remove("pathPoints");
                        JsonValue path=new JsonValue(JsonValue.ValueType.array);
                        if(!unit.get("moveTarget").isNull())path.addChild(copy(unit.get("moveTarget")));
                        unit.addChild("pathPoints",path);
                    }
                }
                missingCheck.accept(row);
            }});
            require(!missingCheck.ready(),"Arrival without "+missing+" became ready");
        }
        for(String mutation:List.of("goal","speed","path","position","generation","time")) {
            List<JsonValue> bad=new ArrayList<>();for(JsonValue row:actual)bad.add(copy(row));
            JsonValue last=bad.stream().filter(r->r.getString("kind").equals("observation")&&r.getString("phase").equals("step")).reduce((a,b)->b).orElseThrow();
            JsonValue unit=SandboxFunctionalOracle.unit(last.get("sample"),"u1");
            switch(mutation) {
                case "goal":unit.remove("moveTarget");unit.addChild("moveTarget",new JsonReader().parse("{x:350,y:110}"));break;
                case "speed":unit.get("speed").set(1L,null);break;
                case "path":unit.get("pathPoints").addChild(new JsonReader().parse("{x:350,y:110}"));break;
                case "position":unit.get("position").get("x").set(400L,null);break;
                case "generation":last.get("generation").set(2L,null);break;
                case "time":last.get("sample").get("simulationTime").set(9999L,null);break;
            }
            SandboxExploreOracle failed=checker(bad,"MEMORY");rejected(()->{for(JsonValue row:postCommandRows(bad))failed.accept(row);});require(!failed.ready(),"Late "+mutation+" failure lost");
        }
        System.out.println("PASS constructed first arrival2499+tail, late2500, missing discovery/reroute and post-arrival goal/speed/path/position/generation/time failures");
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("New output directory required");Path out=Path.of(args[0]);
        List<JsonValue> memory=null;
        for(SandboxMode mode:SandboxMode.values())for(boolean shared:new boolean[]{false,true}) {
            var stream=SandboxFunctionalOracle.Stream.forLongTrace();long[] sequence={0};boolean[] arrived={false};
            SandboxExploreOracle[] current={null};int[] cursor={0};var failures=new SandboxFunctionalOracle.Failures();
            Path leaf=out.resolve(mode.name()+"-shared-"+shared+"-sc07-host.jsonl");
            try(var raw=Files.newOutputStream(leaf,StandardOpenOption.CREATE_NEW)) {
                ClientEvidence evidence=(kind,values)->{
                    try {
                        Map<String,Object> encoded=AndroidKnowledgeEncoding.record(kind,values);encoded.put("generation",1);
                        encoded.put("kind",kind);encoded.put("sequence",sequence[0]++);encoded.put("timeMillis",System.currentTimeMillis());
                        byte[] bytes=(SandboxKnowledgeEncodingTest.json(encoded)+"\n").getBytes(StandardCharsets.UTF_8);
                        raw.write(bytes);raw.flush();stream.feed(bytes,0,false);
                        if(current[0]!=null)cursor[0]=current[0].drain(stream,failures,cursor[0]);
                        if(kind.equals("observation")&&values.get("phase").equals("step"))
                            for(JsonValue event:stream.records.get(stream.records.size()-1).get("sample").get("events"))
                                if(event.getString("kind").equals("movement-arrived")&&event.getString("unitId").equals("u1"))arrived[0]=true;
                    }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
                };
                try(var session=new SandboxSession(ControlledScenario.INSTANCE.load(Path.of("TestLab/local/P002-知识沙盒输入").toAbsolutePath()),evidence,new SandboxOptions(mode,shared))) {
                    session.select(new Position(110,110));session.move(new Position(350,110));
                    current[0]=checker(stream.records,mode.name());cursor[0]=stream.records.size();
                    for(int tick=0;tick<2500&&!arrived[0];tick++)session.tick();
                    require(arrived[0],"Real scenario did not arrive within2500");for(int tail=0;tail<3;tail++)session.tick();
                }
            }
            var oracle=Objects.requireNonNull(current[0]);stream.applicationErrors(failures);
            require(oracle.ready()&&failures.primary==null&&failures.secondary.isEmpty()&&cursor[0]==stream.records.size(),"Real per-record drain failed");
            stream.checkAwait(true,0);stream.complete();
            require(Files.size(leaf)==stream.bytes,"Host raw byte count mismatch");
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(leaf)));
            System.out.println(mode+" shared="+shared+" firstArrival="+oracle.terminal().get("sample").getLong("stepIndex")
                +" discovery="+oracle.discoveryStep()+" rerouted="+oracle.rerouted()+" steps="+stream.steps+" bytes="+stream.bytes+" sha256="+hash);
            if(mode==SandboxMode.MEMORY&&!shared)memory=stream.records;
        }
        negatives(Objects.requireNonNull(memory));
    }
}
