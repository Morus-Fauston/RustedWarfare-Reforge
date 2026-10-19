package com.rustedwarfare.reforge.android.ownruns;

import com.rustedwarfare.reforge.android.metadata.MetadataProbe;
import java.util.LinkedHashMap;
import java.util.Map;

/** OS/clock/transport boundary tests through the public protection reader seam. */
public final class OwnRunsProbeHostTest {
    static int checks;
    static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static String repeated(char c) { StringBuilder b=new StringBuilder(); for(int i=0;i<64;i++) b.append(c); return b.toString(); }
    static Map<String,String> request() {
        Map<String,String> m=new LinkedHashMap<String,String>();
        m.put("schema","e9-ownruns-request-v1"); m.put("mode","inventory"); m.put("manifest","-");
        m.put("bindingId","host-fixture"); m.put("mainApkSha256",repeated('a')); m.put("mainApkBytes","12");
        m.put("testApkSha256",repeated('b')); m.put("testApkBytes","13");
        m.put("certificateSha256",repeated('c')); m.put("certificateBytes","2"); return m;
    }
    static class Host implements OwnRunsProbe.Access, OwnRunsProbe.Transport {
        int finishes; boolean valid;
        int opens; final java.util.List<String> calls=new java.util.ArrayList<String>();
        final java.util.List<String> frames=new java.util.ArrayList<String>();
        public OwnRunsProbe.Stat lstat(String path) throws Exception {
            calls.add("stat:"+path);
            return new OwnRunsProbe.Stat(path.endsWith("/primary")?0120777:path.endsWith(".jsonl")?0100600:0040700,0,0,1,Integer.toUnsignedLong(path.hashCode()),path.endsWith(".jsonl")?3:0,1,1,1);
        }
        public String readlink(String path) { calls.add("link:"+path);return "/storage/emulated/0"; }
        public String[] list(String path) throws Exception {
            calls.add("list:"+path);
            return path.endsWith("/runs")?new String[]{"run-1-aaaaaaaa"}:new String[]{"client-diagnostic.jsonl"};
        }
        public OwnRunsProbe.OpenFile open(final String path) throws Exception {
            calls.add("open:"+path);opens++;
            return new OwnRunsProbe.OpenFile() {
                boolean done;
                public OwnRunsProbe.Stat fstat() throws Exception {return lstat(path);}
                public int read(byte[] b) {if(done)return -1;done=true;b[0]=97;b[1]=98;b[2]=99;return 3;}
                public void close() { }
            };
        }
        public void observeIdentity(MetadataProbe.Identity i) {
            i.processUid=valid?10053:2000; i.processPid=5; i.processUser0=true;
            i.processName=MetadataProbe.MAIN; i.sdkInt=35;
            fill(i.main,MetadataProbe.MAIN,10053,repeated('a'),12,"/data/app/main/base.apk");
            fill(i.test,MetadataProbe.TEST,10052,repeated('b'),13,"/data/app/test/base.apk");
        }
        public long utcMillis() { return 1000; }
        public long monotonicNanos() { return 2000; }
        public void record(long seq,String kind,int part,boolean last,String text) { frames.add(text); }
        public void finish(OwnRunsProbe.Result result) { finishes++; }
    }
    static void fill(MetadataProbe.PackageIdentity p,String name,int uid,String hash,long bytes,String path) {
        p.contextPackage=name;p.packageName=name;p.processName=name;p.contextUid=uid;p.uid=uid;
        p.sourceDir=path;p.publicSourceDir=path;p.splitCount=0;p.signerCount=1;
        p.apkSha256=hash;p.apkBytes=bytes;p.certificateSha256=repeated('c');p.certificateBytes=2;p.certificateDerHex="abcd";
    }
    static OwnRunsProbe.Result run(Host h) {h.valid=true;return OwnRunsProbe.run(h,h,request());}
    static void refused(Host h,String label) {
        OwnRunsProbe.Result r=run(h);check(!r.successful()&&h.opens==0,label+" must stop before content open");
    }
    static Map<String,String> backup(String manifest) {
        Map<String,String> b=request();b.put("mode","backup");b.put("manifest",manifest);
        try{b.put("manifestSha256",OwnRunsProbe.hex(java.security.MessageDigest.getInstance("SHA-256").digest(manifest.getBytes(java.nio.charset.StandardCharsets.US_ASCII))));}
        catch(Exception e){throw new AssertionError(e);}return b;
    }
    static String joined(Host h) {StringBuilder b=new StringBuilder();for(String f:h.frames)b.append(f);return b.toString();}
    static void failureCases() {
        for(final String path:java.util.Arrays.asList("/storage","/storage/self","/storage/self/primary","/storage/emulated","/storage/emulated/0","/storage/emulated/0/Android","/storage/emulated/0/Android/data",OwnRunsProbe.FILES,OwnRunsProbe.ROOT,OwnRunsProbe.ROOT+"/run-1-aaaaaaaa",OwnRunsProbe.ROOT+"/run-1-aaaaaaaa/"+OwnRunsProbe.LEAF)) {
            Host h=new Host(){public OwnRunsProbe.Stat lstat(String p)throws Exception{OwnRunsProbe.Stat v=super.lstat(p);return p.equals(path)?new OwnRunsProbe.Stat(p.endsWith("/primary")?0040700:0120777,0,0,v.device,v.inode,v.size,1,1,1):v;}};
            refused(h,"link/type at "+path);
        }
        Host h=new Host(){public String readlink(String p){return "/storage/emulated/10";}};refused(h,"link literal drift");
        for(final String[] names:new String[][]{null,{"unknown"},{"run-1-aaaaaaaa","run-1-aaaaaaaa"},{"../run-1-aaaaaaaa"},{"run-1-aaaaaaaa/leaf"},{"run-1-aaaaaaa\ud800"},{"run-1-aaaaaaa\u0000"}}) {
            h=new Host(){public String[] list(String p)throws Exception{return p.equals(OwnRunsProbe.ROOT)?names:super.list(p);}};
            refused(h,"unknown/null/duplicate run names");
            if(names!=null&&names[0].indexOf('\ud800')>=0)check(joined(h).contains("\\ud800"),"invalid returned Java surrogate retained escaped before rejection");
        }
        for(final String[] names:new String[][]{null,{"unknown"},{OwnRunsProbe.LEAF,OwnRunsProbe.LEAF},{"../"+OwnRunsProbe.LEAF}}) {
            h=new Host(){public String[] list(String p)throws Exception{return p.equals(OwnRunsProbe.ROOT)?super.list(p):names;}};refused(h,"unknown/null/duplicate leaves");
        }
        h=new Host(){public String[] list(String p)throws Exception{if(p.equals(OwnRunsProbe.ROOT)){String[] n=new String[129];for(int i=0;i<n.length;i++)n[i]="run-"+i+"-aaaaaaaa";return n;}return super.list(p);}};refused(h,"run budget");
        h=new Host(){public OwnRunsProbe.Stat lstat(String p)throws Exception{OwnRunsProbe.Stat x=super.lstat(p);return p.endsWith(".jsonl")?new OwnRunsProbe.Stat(x.mode,0,0,1,x.inode,OwnRunsProbe.MAX_LEAF+1,1,1,1):x;}};refused(h,"leaf budget");
        h=new Host(){public String[] list(String p)throws Exception{return p.equals(OwnRunsProbe.ROOT)?new String[]{"run-1-aaaaaaaa","run-2-aaaaaaaa","run-3-aaaaaaaa","run-4-aaaaaaaa","run-5-aaaaaaaa"}:super.list(p);}public OwnRunsProbe.Stat lstat(String p)throws Exception{OwnRunsProbe.Stat x=super.lstat(p);return p.endsWith(".jsonl")?new OwnRunsProbe.Stat(x.mode,0,0,1,x.inode,OwnRunsProbe.MAX_LEAF,1,1,1):x;}};refused(h,"total byte budget");
        h=new Host(){public OwnRunsProbe.Stat lstat(String p)throws Exception{if(p.equals(OwnRunsProbe.ROOT))throw new OwnRunsProbe.PlatformFailure(2,new java.io.IOException("fixture ENOENT"));return super.lstat(p);}public String[] list(String p)throws Exception{return p.equals(OwnRunsProbe.FILES)?new String[]{"other-own-name"}:super.list(p);}};
        OwnRunsProbe.Result r=run(h);check(r.successful()&&h.opens==0&&joined(h).contains("provenByOwnParentNames"),"missing own runs proven by successful verified own parent names");
        h=new Host(){public OwnRunsProbe.Stat lstat(String p)throws Exception{if(p.equals("/storage/emulated/0/Android/data"))throw new OwnRunsProbe.PlatformFailure(2,new java.io.IOException("fixture ENOENT"));return super.lstat(p);}};
        r=run(h);check(!r.successful()&&r.primaryErrno==2&&h.calls.toString().indexOf("list:")<0,"early system ENOENT does not trigger sibling names or absence claim");
        h=new Host(){public String[] list(String p)throws Exception{return p.equals(OwnRunsProbe.ROOT)?super.list(p):new String[0];}};
        r=run(h);check(r.successful()&&h.opens==0&&r.manifest.contains("run-1-aaaaaaaa\t-\n"),"empty run preserved without fabricated leaf");
        h=new Host(){public OwnRunsProbe.Stat lstat(String p)throws Exception{OwnRunsProbe.Stat x=super.lstat(p);return new OwnRunsProbe.Stat(x.mode,x.uid,x.gid,x.device,x.inode,x.size,x.modifiedSeconds,x.changedSeconds,calls.size());}};
        check(run(h).successful(),"atime only is observed but not key-attribute drift");
        h=new Host(){int seen;public OwnRunsProbe.Stat lstat(String p)throws Exception{OwnRunsProbe.Stat x=super.lstat(p);if(p.equals(OwnRunsProbe.ROOT)&&++seen>1)return new OwnRunsProbe.Stat(x.mode,x.uid,x.gid,x.device,x.inode+1,x.size,1,1,1);return x;}};refused(h,"parent inode drift");
        for(final String field:java.util.Arrays.asList("schema","mode","manifest","mainApkBytes","certificateSha256","other")) {
            h=new Host();h.valid=true;Map<String,String>b=request();b.put(field,"bad");r=OwnRunsProbe.run(h,h,b);check(!r.successful()&&h.calls.isEmpty(),"malformed request issues zero path calls: "+field);
        }
        for(final String fault:java.util.Arrays.asList("open","read","zero","negative","eof","growth","fstat","close","read-close","timeout")) {
            final RuntimeException marker=new IllegalStateException("injected read first cause");
            h=new Host(){
                public long monotonicNanos(){return fault.equals("timeout")&&opens>0?OwnRunsProbe.WINDOW_NANOS+2000:2000;}
                public OwnRunsProbe.OpenFile open(final String p)throws Exception {
                    if(fault.equals("open"))throw new OwnRunsProbe.PlatformFailure(13,new java.io.IOException("injected OS refusal"));
                    final OwnRunsProbe.OpenFile f=super.open(p);
                    return new OwnRunsProbe.OpenFile(){
                        public OwnRunsProbe.Stat fstat()throws Exception{if(fault.equals("fstat"))return new OwnRunsProbe.Stat(0040700,0,0,1,1,3,1,1,1);return f.fstat();}
                        public int read(byte[] b)throws Exception{if(fault.startsWith("read"))throw marker;if(fault.equals("zero"))return 0;if(fault.equals("negative"))return -2;if(fault.equals("eof"))return -1;if(fault.equals("growth"))return 4;return f.read(b);}
                        public void close()throws Exception{if(fault.equals("close")||fault.equals("read-close"))throw new java.io.IOException("injected close failure");f.close();}
                    };
                }
            };
            r=run(h);check(!r.successful(),"read lifecycle failure cannot complete: "+fault);
            if(fault.equals("read-close"))check(r.primaryFailure==marker&&r.primaryStage.equals("read")&&r.secondaryFailures.size()==1,"close failure retains read first cause and stage independently");
            if(fault.equals("open"))check(r.primaryErrno==13,"actual seam OS errno retained");
        }
        h=new Host(){public void record(long s,String k,int p,boolean l,String text){throw new IllegalStateException("injected status failure");}public void finish(OwnRunsProbe.Result r){finishes++;throw new IllegalStateException("injected finish failure");}};
        r=run(h);check(!r.successful()&&r.primaryFailure==null&&r.transportFailures.size()==2&&h.opens==0&&h.finishes==1,"transport refusal stops before paths and closes separately");
        Host base=new Host();OwnRunsProbe.Result good=run(base);String accepted=good.manifest.replace("\t0\n","\t1\n");
        for(final String bad:new String[]{accepted+"\n",accepted.replace("run-1-aaaaaaaa","../run-1-aaaaaaaa"),accepted.replace("\t1\n","\t2\n"),accepted.replace("\t3\t","\t04\t")}) {
            h=new Host();h.valid=true;r=OwnRunsProbe.run(h,h,backup(bad));check(!r.successful()&&h.calls.isEmpty(),"invalid backup manifest rejected before paths");
        }
        h=new Host();h.valid=true;r=OwnRunsProbe.run(h,h,backup(accepted.replace("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",repeated('d'))));
        check(!r.successful()&&"manifest-hash".equals(r.primaryStage),"accepted hash mismatch never completes transfer");
        h=new Host();h.valid=true;r=OwnRunsProbe.run(h,h,backup(accepted.replace("run-1-aaaaaaaa","run-2-aaaaaaaa")));
        check(!r.successful()&&h.opens==0,"accepted complete run set mismatch before contents");
        h=new Host();h.valid=true;r=OwnRunsProbe.run(h,h,backup(good.manifest));
        check(r.successful()&&!joined(h).contains("\"base64\""),"already backed up copy=0 hashes full leaf without duplicate content transfer");
    }
    public static void main(String[] args) {
        Host h=new Host(); OwnRunsProbe.Result r=OwnRunsProbe.run(h,h,request());
        check(r.primaryFailure!=null,"wrong actual identity must reject");
        check(r.identity.processUid==2000,"record actual rejected process UID");
        check(h.finishes==1&&!r.successful()&&h.calls.isEmpty(),"reject still closes final transport without path queries");
        if(!args[0].equals("identity")) {
            h=new Host();r=run(h);
            check(r.successful(),"valid inventory must close all checks and complete");
            check(h.opens==1,"exact known leaf read once for complete SHA");
            check(joined(h).contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),"independent SHA256 abc oracle");
            if(args[0].equals("backup")||args[0].equals("all")) {
                Map<String,String> b=backup(r.manifest.replace("\t0\n","\t1\n"));
                h=new Host();h.valid=true;r=OwnRunsProbe.run(h,h,b);
                check(r.successful(),"accepted manifest backup must complete");
                check(joined(h).contains("\"base64\":\"YWJj\""),"backup carries exact abc bytes with offset and hash closure");
            }
        }
        if(args[0].equals("all"))failureCases();
        if(args[0].equals("protocol")||args[0].equals("all"))OwnRunsProtocolHostTest.run();
        if(args[0].equals("filesystem")||args[0].equals("all"))OwnRunsFilesystemHostTest.run();
        System.out.println("PASS "+checks+" ownruns checks");
    }
}
