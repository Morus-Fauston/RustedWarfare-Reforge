package com.rustedwarfare.reforge.reference.instrumentation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Separate bounded motion chain, linked to the existing initial snapshot's raw hash. */
final class SceneWire implements SceneDriver.Sink {
    interface Output { void send(String raw) throws Throwable; }
    private final Output output;
    private final String runId,mode,targetHash,testHash,initialHash;
    private final ScenePlan plan;private final Map<String,Object> sceneInitial;
    private final List<String> hashes=new ArrayList<String>();
    private int bytes,phases,completed;
    private String lastEndReason;
    private boolean closed;
    SceneWire(Output output,String runId,String mode,String targetHash,String testHash,String initialHash,ScenePlan plan,Map<String,Object> sceneInitial) {
        this.output=output;this.runId=runId;this.mode=mode;this.targetHash=targetHash;this.testHash=testHash;this.initialHash=initialHash;this.plan=plan;this.sceneInitial=sceneInitial;
    }
    void begin() throws Throwable {
        InitialProtocol.require(hashes.isEmpty(),"motion-wire-begin");
        Map<String,Object> payload=map();List<Long> durations=new ArrayList<Long>();for(long d:plan.durations())durations.add(d);
        payload.put("wallLimitsMillis",durations);payload.put("stopRequestedQ",plan.stop?30:null);
        payload.put("caseId",plan.id);payload.put("sceneInitial",sceneInitial);payload.put("capacityPerPhase",512);payload.put("maximumPhases",4);payload.put("pauseBetweenPhases",true);append("begin",payload);
    }
    private void append(String kind,Map<String,Object> payload) throws Throwable {
        InitialProtocol.require(!closed,"motion-wire-closed");Map<String,Object> row=map();
        row.put("schemaVersion",1);row.put("profile","r17.O.v2."+plan.id);row.put("runId",runId);row.put("mode",mode);
        row.put("targetApkSha256",targetHash);row.put("testApkSha256",testHash);row.put("r12InitialRecordSha256",initialHash);
        row.put("sequence",hashes.size());row.put("previousRecordSha256",hashes.isEmpty()?null:hashes.get(hashes.size()-1));
        row.put("kind",kind);row.put("emittedNanoTime",Long.toString(System.nanoTime()));row.put("payload",payload);
        String raw=InitialProtocol.json(row);byte[] encoded=raw.getBytes(StandardCharsets.UTF_8);
        InitialProtocol.require(encoded.length<=16384 && bytes+encoded.length<=1048576 && hashes.size()<256,"motion-wire-limit");
        // A send failure does not become a successful chain member.
        output.send(raw);hashes.add(InitialProtocol.sha256(encoded));bytes+=encoded.length;
    }
    @Override public void phase(Map<String,Object> record) throws Throwable {
        lastEndReason=(String)record.get("endReason");
        InitialProtocol.require(!hashes.isEmpty() && Integer.valueOf(phases).equals(record.get("phase")) && phases<4,"motion-wire-phase");
        Map<String,Object> header=new LinkedHashMap<String,Object>(record);header.remove("before");header.remove("after");header.remove("trace");
        append("phase-summary",header);
        for(String side:new String[]{"before","after"})if(record.containsKey(side)){
            Map<String,Object> p=map();p.put("phase",phases);p.put("side",side);p.put("snapshot",record.get(side));append("boundary",p);
        }
        if(record.containsKey("trace")){
            @SuppressWarnings("unchecked") Map<String,Object> trace=(Map<String,Object>)record.get("trace");
            Map<String,Object> metadata=new LinkedHashMap<String,Object>(trace);metadata.remove("rows");
            metadata.put("fields",java.util.Arrays.asList((String[])trace.get("fields")));
            metadata.put("enumNames",java.util.Arrays.asList((String[])trace.get("enumNames")));
            append("trace-metadata",metadata);
            long[][] rows=(long[][])trace.get("rows");
            for(int start=0;start<rows.length;start+=8){
                List<Object> block=new ArrayList<Object>();
                for(int i=start;i<Math.min(start+8,rows.length);i++){
                    List<Object> values=new ArrayList<Object>();for(long value:rows[i])values.add(value);block.add(values);
                }
                Map<String,Object> p=map();p.put("phase",phases);p.put("startIndex",start);p.put("rows",block);append("trace-chunk",p);
            }
        }
        Map<String,Object> end=map();end.put("phase",phases);end.put("completed",record.get("completed"));end.put("firstError",record.get("firstError"));
        append("phase-end",end);phases++;if(Boolean.TRUE.equals(record.get("completed")))completed++;
    }
    Map<String,Object> end(boolean success) throws Throwable {
        return end(success,new ArrayList<Object>(),map());
    }
    void seeds(List<Object> rows) throws Throwable {Map<String,Object> p=map();p.put("rows",rows);append("seeds-after-motion",p);}
    Map<String,Object> end(boolean success,List<Object> driverErrors,Map<String,Object> finalFacts) throws Throwable {
        InitialProtocol.require(!hashes.isEmpty(),"motion-wire-no-begin");
        InitialProtocol.require(!success || (phases>0 && phases<=plan.durations().length && completed==phases
            && (phases==plan.durations().length || "terminal-plus-18q".equals(lastEndReason) || plan.stationary)),"motion-wire-incomplete-success");
        Map<String,Object> p=map();p.put("success",success);p.put("phaseCount",phases);p.put("completedPhaseCount",completed);
        p.put("driverErrors",new ArrayList<Object>(driverErrors));p.put("finalFacts",finalFacts);
        p.put("priorRecordHashes",new ArrayList<String>(hashes));p.put("priorRecordCount",hashes.size());append("end",p);closed=true;
        Map<String,Object> summary=map();summary.put("success",success);summary.put("phaseCount",phases);summary.put("completedPhaseCount",completed);
        summary.put("recordHashes",new ArrayList<String>(hashes));summary.put("recordCount",hashes.size());summary.put("bytes",bytes);return summary;
    }
    private static Map<String,Object> map(){return new LinkedHashMap<String,Object>();}
}
