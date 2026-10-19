package com.rustedwarfare.reforge.android.ownruns;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Exercises the public frame wire contract, including receiver rejection of damaged streams. */
final class OwnRunsProtocolHostTest {
    private static final class Frame {
        final long seq;final String kind,text;final int part;final boolean last;
        Frame(long seq,String kind,int part,boolean last,String text){this.seq=seq;this.kind=kind;this.part=part;this.last=last;this.text=text;}
    }
    private static final class Wire extends OwnRunsProbeHostTest.Host {
        final List<Frame> wire=new ArrayList<Frame>();
        final byte[] content=new byte[20000];
        Wire(){Arrays.fill(content,(byte)'x');}
        @Override public OwnRunsProbe.Stat lstat(String p)throws Exception {
            OwnRunsProbe.Stat x=super.lstat(p);
            return p.endsWith(".jsonl")?new OwnRunsProbe.Stat(x.mode,0,0,1,x.inode,content.length,1,1,1):x;
        }
        @Override public OwnRunsProbe.OpenFile open(final String p) {
            opens++;return new OwnRunsProbe.OpenFile(){int offset;
                public OwnRunsProbe.Stat fstat()throws Exception{return lstat(p);}
                public int read(byte[] b){if(offset==content.length)return -1;int n=Math.min(b.length,content.length-offset);System.arraycopy(content,offset,b,0,n);offset+=n;return n;}
                public void close(){ }
            };
        }
        @Override public void record(long s,String k,int p,boolean l,String text) {
            OwnRunsProbeHostTest.check(text.length()<=8192,"wire frame bounded below Binder limit");
            boolean ascii=true;for(int i=0;i<text.length();i++)ascii&=text.charAt(i)<=127;
            OwnRunsProbeHostTest.check(ascii,"wire ASCII does not corrupt UTF16 names");
            wire.add(new Frame(s,k,p,l,text));
        }
        @Override public void finish(OwnRunsProbe.Result r)throws RuntimeException {
            finishes++;
            try{OwnRunsProbe.frames(this,r.eventCount,"summary",r.toJson());}catch(Exception e){throw new IllegalStateException(e);}
        }
    }
    static final class Received {
        final ByteArrayOutputStream data=new ByteArrayOutputStream();String summary;long eventCount;
    }
    private static Received receive(List<Frame> frames,OwnRunsProbe.Result result)throws Exception {
        Received out=new Received();MessageDigest events=MessageDigest.getInstance("SHA-256");
        long seq=0;int part=0;String kind="event";StringBuilder text=new StringBuilder();boolean summaryClosed=false;
        Pattern chunk=Pattern.compile("\\\"run\\\":\\\"run-1-aaaaaaaa\\\",\\\"offset\\\":([0-9]+),\\\"base64\\\":\\\"([A-Za-z0-9+/=]*)\\\"");
        for(Frame f:frames) {
            if(summaryClosed||f.seq!=seq||f.part!=part||f.text.length()>8192||!(f.kind.equals("event")||f.kind.equals("summary")))throw new IllegalStateException("Missing/duplicate/out-of-order frame");
            if(part==0)kind=f.kind;
            if(!kind.equals(f.kind)||kind.equals("summary")&&seq!=result.eventCount)throw new IllegalStateException("Mixed or early summary");
            text.append(f.text);part++;
            if(f.last) {
                String json=text.toString();
                if(kind.equals("event")) {
                    events.update(json.getBytes(StandardCharsets.US_ASCII));events.update((byte)10);out.eventCount++;
                    Matcher m=chunk.matcher(json);
                    if(m.find()) {
                        if(Long.parseLong(m.group(1))!=out.data.size())throw new IllegalStateException("Content offset gap/duplicate");
                        byte[] b=Base64.getDecoder().decode(m.group(2));if(b.length>8192)throw new IllegalStateException("Content chunk too large");out.data.write(b);
                    }
                }else {out.summary=json;summaryClosed=true;}
                seq++;part=0;text.setLength(0);
            }
        }
        if(!summaryClosed||part!=0||out.eventCount!=result.eventCount||!OwnRunsProbe.hex(events.digest()).equals(result.eventSha256)||!out.summary.equals(result.toJson()))throw new IllegalStateException("Unclosed events/summary/digest");
        return out;
    }
    static void run() {
        try {
            Wire inventory=new Wire();OwnRunsProbe.Result initial=OwnRunsProbeHostTest.run(inventory);
            OwnRunsProbeHostTest.check(initial.successful(),"multi-chunk inventory completes");
            Wire backup=new Wire();backup.valid=true;
            OwnRunsProbe.Result done=OwnRunsProbe.run(backup,backup,OwnRunsProbeHostTest.backup(initial.manifest.replace("\t0\n","\t1\n")));
            Received received=receive(backup.wire,done);
            OwnRunsProbeHostTest.check(done.successful()&&Arrays.equals(backup.content,received.data.toByteArray()),"ordered host decoding preserves all 20000 original bytes");
            OwnRunsProbeHostTest.check(received.data.size()==20000&&OwnRunsProbe.hex(MessageDigest.getInstance("SHA-256").digest(received.data.toByteArray())).equals(done.leafHashes.get("run-1-aaaaaaaa")),"host byte count and complete SHA close");
            for(int fault=0;fault<4;fault++) {
                List<Frame> bad=new ArrayList<Frame>(backup.wire);
                if(fault==0)bad.remove(1);if(fault==1)bad.add(1,bad.get(1));if(fault==2)Collections.swap(bad,1,2);if(fault==3)bad.remove(bad.size()-1);
                boolean rejected=false;try{receive(bad,done);}catch(IllegalStateException expected){rejected=true;}
                OwnRunsProbeHostTest.check(rejected,"receiver rejects missing/duplicate/order/unclosed damage "+fault);
            }
            OwnRunsProbeHostTest.Host names=new OwnRunsProbeHostTest.Host(){public String[] list(String p)throws Exception{if(p.equals(OwnRunsProbe.ROOT)){char[] c=new char[4000];Arrays.fill(c,'\ud800');return new String[]{new String(c)};}return super.list(p);}public void record(long s,String k,int p,boolean l,String text){OwnRunsProbeHostTest.check(text.length()<=8192,"oversized invalid names bounded");super.record(s,k,p,l,text);}};
            OwnRunsProbe.Result rejected=OwnRunsProbeHostTest.run(names);
            OwnRunsProbeHostTest.check(!rejected.successful()&&names.opens==0&&OwnRunsProbeHostTest.joined(names).contains("\\ud800\\ud800"),"oversized invalid Java names retained losslessly and never read");
            System.out.println("PROTOCOL: complete 20000-byte roundtrip; four damaged frame sets rejected; oversized surrogate names preserved/rejected");
        }catch(Exception e){throw new AssertionError("Protocol verification failed",e);}
    }
}
