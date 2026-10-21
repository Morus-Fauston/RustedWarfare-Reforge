package com.rustedwarfare.reforge.reference.instrumentation;
import java.util.*;
public final class MotionWireTest {
    static void check(boolean b){if(!b)throw new AssertionError();}
    static Map<String,Object> map(){return new LinkedHashMap<String,Object>();}
    static Map<String,Object> phase(int n,boolean trace){
        Map<String,Object> row=map();row.put("phase",n);row.put("mode",trace?"on":"bare");row.put("completed",true);row.put("firstError",null);
        row.put("before",map());row.put("after",map());
        if(trace){
            Map<String,Object> t=map();t.put("phase",n);t.put("fields",new String[]{"fixture"});t.put("enumNames",new String[]{"raw-name"});
            t.put("rows",new long[][]{{1,2,Float.floatToRawIntBits(1f),Long.MIN_VALUE,5,2,Float.floatToRawIntBits(2f),Long.MAX_VALUE}});row.put("trace",t);
        }
        return row;
    }
    static MotionWire wire(MotionWire.Output output){return new MotionWire(output,"00000000-0000-0000-0000-000000000001","on",repeat('a'),repeat('b'),repeat('c'));}
    static String repeat(char c){char[] chars=new char[64];Arrays.fill(chars,c);return new String(chars);}
    public static void main(String[] args)throws Throwable{
        final List<String> raw=new ArrayList<String>();
        MotionWire wire=wire(new MotionWire.Output(){public void send(String text){raw.add(text);}});
        wire.begin();for(int n=0;n<4;n++)wire.phase(phase(n,true));Map<String,Object> summary=wire.end(true);
        check(summary.get("success").equals(true) && summary.get("phaseCount").equals(4));
        List<String> expected=new ArrayList<String>();for(String s:raw)expected.add(InitialProtocol.sha256(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check(expected.equals(summary.get("recordHashes")) && summary.get("recordCount").equals(raw.size()));
        for(int i=1;i<raw.size();i++)check(raw.get(i).contains("\"previousRecordSha256\":\""+expected.get(i-1)+"\""));
        check(raw.get(0).contains("\"previousRecordSha256\":null"));
        check(raw.toString().contains(Long.toString(Long.MIN_VALUE)) && raw.toString().contains(Long.toString(Long.MAX_VALUE)));
        boolean rejected=false;try{wire.end(true);}catch(IllegalArgumentException e){rejected=true;}check(rejected);
        MotionWire incomplete=wire(new MotionWire.Output(){public void send(String text){}});incomplete.begin();
        rejected=false;try{incomplete.end(true);}catch(IllegalArgumentException e){rejected=true;}check(rejected);incomplete.end(false);
        final List<String> sent=new ArrayList<String>();final int[] calls={0};
        MotionWire broken=wire(new MotionWire.Output(){public void send(String text){if(++calls[0]==4)throw new IllegalStateException("injected-output-failure");sent.add(text);}});
        broken.begin();rejected=false;try{broken.phase(phase(0,true));}catch(IllegalStateException e){rejected=true;}check(rejected);
        Map<String,Object> failed=broken.end(false);check(failed.get("success").equals(false) && failed.get("phaseCount").equals(0));
        check(failed.get("recordCount").equals(sent.size()));
        System.out.println("PASS motion wire: raw longs, chunks, chain, order, complete/incomplete, output failure");
    }
}
