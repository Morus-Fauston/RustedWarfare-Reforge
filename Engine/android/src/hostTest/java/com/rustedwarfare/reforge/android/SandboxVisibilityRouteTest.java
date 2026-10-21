package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonValue;
import com.rustedwarfare.reforge.client.*;
import com.rustedwarfare.reforge.core.sim.Position;
import com.rustedwarfare.reforge.core.sim.UnitObservation;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.security.MessageDigest;

/** Actual public S03 route/effect preparation. No UI, PixelCopy or Android result is claimed. */
public final class SandboxVisibilityRouteTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static com.rustedwarfare.reforge.core.sim.UnitObservation own(SandboxSession session){
        return session.getObservation().getUnits().stream().filter(u->u.getUnitId().equals("u1")).findFirst().orElseThrow();
    }
    private static boolean visible(SandboxSession session){return session.getObservation().getUnits().stream().anyMatch(u->u.getUnitId().equals("u3"));}
    private static long count(List<JsonValue> rows,String kind){return rows.stream().filter(r->r.getString("kind").equals(kind)).count();}
    private static void tick(SandboxSession session,int[] ticks){require(ticks[0]<2500,"World business tick budget exhausted");session.tick();ticks[0]++;}
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("New evidence directory required");Path out=Path.of(args[0]);
        for(SandboxMode mode:new SandboxMode[]{SandboxMode.MEMORY,SandboxMode.HARDCORE}){
            var stream=SandboxFunctionalOracle.Stream.forLongTrace();long[] sequence={0};int[] ticks={0};String[] phase={"approach"};
            Path leaf=out.resolve(mode+"-visibility-route-host.jsonl");
            try(var raw=Files.newOutputStream(leaf,StandardOpenOption.CREATE_NEW)){
                ClientEvidence sink=(kind,values)->{
                    try{
                        Map<String,Object> row=AndroidKnowledgeEncoding.record(kind,values);row.put("kind",kind);row.put("generation",1);
                        row.put("sequence",sequence[0]++);row.put("hostPhase",phase[0]);row.put("timeMillis",System.currentTimeMillis());
                        byte[] bytes=(SandboxKnowledgeEncodingTest.json(row)+"\n").getBytes(StandardCharsets.UTF_8);
                        raw.write(bytes);raw.flush();stream.feed(bytes,0,false);
                    }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
                };
                try(var session=new SandboxSession(ControlledScenario.INSTANCE.load(Path.of("TestLab/local/P002-知识沙盒输入").toAbsolutePath()),sink,new SandboxOptions(mode,false))){
                    require(!visible(session),"u3 initially visible without shared vision");
                    session.select(new Position(110,110));session.move(new Position(530,270));
                    long firstVisible=-1;
                    while(own(session).getMoveTarget()!=null||own(session).getSpeed()!=0){
                        tick(session,ticks);if(firstVisible<0&&visible(session))firstVisible=session.getObservation().getStepIndex();
                    }
                    require(firstVisible>=0&&visible(session),"Fixed approach did not expose u3 at its stopped endpoint");
                    phase[0]="visible-baseline";session.select(new Position(550,310));
                    require("u3".equals(session.getSelectedId()),"Visible foreign selection failed");
                    for(int i=0;i<3;i++){tick(session,ticks);require(visible(session)&&"u3".equals(session.getSelectedId()),"Visible baseline not stable");}
                    phase[0]="leave";session.select(own(session).getPosition());session.move(new Position(110,290));
                    for(int i=0;i<3;i++)tick(session,ticks); // A concrete input-latency allowance; no Android timing claim.
                    require(visible(session),"u3 lost before actual second selection");
                    session.select(new Position(550,310));require("u3".equals(session.getSelectedId()),"Leave-phase foreign selection failed");
                    while(visible(session))tick(session,ticks);
                    long loss=session.getObservation().getStepIndex();
                    require(session.getSelectedId()==null,"Automatic first-loss selection clearing failed");
                    phase[0]="hidden";
                    for(int i=0;i<3;i++){
                        tick(session,ticks);require(!visible(session)&&session.getSelectedId()==null,"First loss did not remain hidden/cleared");
                        require(own(session).getMoveTarget()!=null&&!own(session).getPathPoints().isEmpty()&&own(session).getSpeed()>0,"Own leaving route was no longer active");
                    }
                    long actions=count(stream.records,"action"),selections=count(stream.records,"selection");
                    phase[0]="hidden-hit";session.tap(new Position(550,310));
                    for(int i=0;i<3;i++){tick(session,ticks);require(!visible(session)&&session.getSelectedId()==null,"Hidden hit restored selection/visibility");}
                    require(count(stream.records,"action")==actions&&count(stream.records,"selection")==selections,"Hidden hit emitted an action/selection");
                    require(actions==6&&selections==4,"Unexpected command/selection history");
                    JsonValue terminal=stream.records.get(stream.records.size()-1);stream.endBusinessAt(terminal,1);
                    System.out.println(mode+" firstVisible="+firstVisible+" firstLoss="+loss+" terminal="+session.getObservation().getStepIndex()
                        +" actions="+actions+" selections="+selections+" autoClear=true hiddenHitNoAction=true");
                }
            }
            stream.checkAwait(true,0);stream.complete();
            require(Files.size(leaf)==stream.bytes,"Raw file byte count mismatch");
            System.out.println(mode+" steps="+stream.steps+" bytes="+stream.bytes+" sha256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(leaf))));
        }
    }
}
