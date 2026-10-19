package com.rustedwarfare.reforge.android.ownruns;

import com.rustedwarfare.reforge.android.metadata.MetadataProbe;
import java.util.Map;
import java.util.Arrays;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Fixed own-APP protection reader; no production runtime dependency. */
public final class OwnRunsProbe {
    private OwnRunsProbe() { }
    public interface Access {
        void observeIdentity(MetadataProbe.Identity identity) throws Exception;
        Stat lstat(String path) throws Exception;
        String readlink(String path) throws Exception;
        String[] list(String path) throws Exception;
        OpenFile open(String path) throws Exception;
        long utcMillis();
        long monotonicNanos();
    }
    public interface OpenFile {
        Stat fstat() throws Exception;
        int read(byte[] bytes) throws Exception;
        void close() throws Exception;
    }
    public static final class Stat {
        public final int mode,uid,gid;
        public final long device,inode,size,modifiedSeconds,changedSeconds,accessedSeconds;
        public Stat(int mode,int uid,int gid,long device,long inode,long size,long modified,long changed,long accessed) {
            this.mode=mode;this.uid=uid;this.gid=gid;this.device=device;this.inode=inode;this.size=size;
            this.modifiedSeconds=modified;this.changedSeconds=changed;this.accessedSeconds=accessed;
        }
    }
    public interface Transport {
        void record(long sequence, String kind, int part, boolean last, String payload) throws Exception;
        void finish(Result result) throws Exception;
    }
    public static final class Result {
        public final MetadataProbe.Identity identity = new MetadataProbe.Identity();
        public Throwable primaryFailure;
        public String primaryStage, primaryPath;
        public final List<Throwable> transportFailures = new ArrayList<Throwable>();
        public final List<Throwable> secondaryFailures = new ArrayList<Throwable>();
        public long startUtcMillis, endUtcMillis, startMonotonicNanos, endMonotonicNanos;
        public Request request;
        public Integer primaryErrno;
        public long eventCount, readBytes;
        public String eventSha256, manifest;
        public final Map<String,String> leafHashes=new LinkedHashMap<String,String>();
        public String toJson() {
            List<Object> tf=new ArrayList<Object>(),sf=new ArrayList<Object>();
            for(Throwable t:transportFailures)tf.add(OwnRunsJson.error(t));for(Throwable t:secondaryFailures)sf.add(OwnRunsJson.error(t));
            return OwnRunsJson.encode(OwnRunsJson.object("schema","e9-ownruns-result-v1","complete",completed,"success",successful(),
                "ownRunsInventoryComplete",successful()&&request!=null&&"inventory".equals(request.mode),
                "backupTransferComplete",successful()&&request!=null&&"backup".equals(request.mode),
                "oldRunsProtected",false,"privateTreeVerified",false,"installAllowed",false,"runtimeVerified",false,
                "applicationExecutedForProduct",false,"readerContext","target-app-instrumentation",
                "binding",request==null?null:request.values,"identity",OwnRunsJson.identity(identity),
                "startUtcMillis",startUtcMillis,"endUtcMillis",endUtcMillis,"startMonotonicNanos",startMonotonicNanos,"endMonotonicNanos",endMonotonicNanos,
                "eventCount",eventCount,"eventSha256",eventSha256,"readBytes",readBytes,"manifest",manifest,
                "primaryStage",primaryStage,"primaryPath",primaryPath,"primaryErrno",primaryErrno,"primaryFailure",OwnRunsJson.error(primaryFailure),
                "transportFailures",tf,"secondaryFailures",sf));
        }
        public boolean completed;
        public boolean successful() { return completed && primaryFailure == null && transportFailures.isEmpty() && secondaryFailures.isEmpty(); }
    }
    public static final class Request {
        public final String mode, bindingId, manifest;
        public final Map<String,String> values;
        final Map<String,ExpectedLeaf> accepted=new LinkedHashMap<String,ExpectedLeaf>();
        Request(Map<String,String> m) {
            require(m!=null,"Missing request");
            HashSet<String> keys=new HashSet<String>(Arrays.asList("schema","mode","bindingId","manifest",
                    "mainApkSha256","mainApkBytes","testApkSha256","testApkBytes","certificateSha256","certificateBytes"));
            if("backup".equals(m.get("mode"))) keys.add("manifestSha256");
            require(m.keySet().equals(keys),"Unknown/missing exact request keys");
            for(String v:m.values()) require(v!=null,"Request values must be strings");
            require("e9-ownruns-request-v1".equals(m.get("schema")),"Wrong request schema");
            mode=m.get("mode"); require("inventory".equals(mode)||"backup".equals(mode),"Unknown mode");
            bindingId=m.get("bindingId"); require(bindingId.matches("[A-Za-z0-9._-]{1,96}"),"Invalid bindingId");
            for(String name:Arrays.asList("mainApkSha256","testApkSha256","certificateSha256"))
                require(m.get(name).matches("[0-9a-f]{64}"),"Invalid full SHA256");
            for(String name:Arrays.asList("mainApkBytes","testApkBytes","certificateBytes")) {
                require(m.get(name).matches("[1-9][0-9]*"),"Invalid positive byte count"); Long.parseLong(m.get(name));
            }
            manifest=m.get("manifest");
            require(!"inventory".equals(mode)||"-".equals(manifest),"Inventory has no content manifest");
            if("backup".equals(mode))parseManifest(m.get("manifestSha256"));
            values=new java.util.LinkedHashMap<String,String>(m);
        }
        void parseManifest(String sha) {
            require(manifest.length()<=65536&&manifest.startsWith("e9-ownruns-manifest-v1\n")&&manifest.endsWith("\n"),"Invalid manifest header/length/end");
            require(sha!=null&&sha.matches("[0-9a-f]{64}"),"Invalid manifest SHA256");
            for(int i=0;i<manifest.length();i++)require(manifest.charAt(i)<128,"Manifest must be ASCII canonical text");
            try {require(sha.equals(hex(MessageDigest.getInstance("SHA-256").digest(manifest.getBytes(StandardCharsets.US_ASCII)))),"Manifest digest differs");}
            catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            String[] lines=manifest.split("\n",-1);long total=0;
            for(int i=1;i<lines.length-1;i++) {
                String[] f=lines[i].split("\t",-1);require((f.length==2||f.length==11)&&validRun(f[0])&&!accepted.containsKey(f[0]),"Unknown/duplicate manifest row");
                require(accepted.size()<128,"Manifest run budget exceeded");
                if(f.length==2){require("-".equals(f[1]),"Invalid empty run");accepted.put(f[0],null);continue;}
                int mode=(int)number(f[1],false,Integer.MAX_VALUE),uid=(int)number(f[2],false,Integer.MAX_VALUE),gid=(int)number(f[3],false,Integer.MAX_VALUE);
                Stat s=new Stat(mode,uid,gid,number(f[4],false,Long.MAX_VALUE),number(f[5],false,Long.MAX_VALUE),number(f[6],false,MAX_LEAF),number(f[7],true,Long.MAX_VALUE),number(f[8],true,Long.MAX_VALUE),0);
                require((mode&0170000)==0100000&&f[9].matches("[0-9a-f]{64}")&&(f[10].equals("0")||f[10].equals("1")),"Invalid accepted diagnostic leaf");
                total=Math.addExact(total,s.size);require(total<=MAX_TOTAL,"Manifest total budget exceeded");
                accepted.put(f[0],new ExpectedLeaf(s,f[9],f[10].equals("1")));
            }
        }
        long number(String text,boolean signed,long max) {
            require(text.matches(signed?"0|-?[1-9][0-9]*":"0|[1-9][0-9]*"),"Noncanonical manifest number");
            long v=Long.parseLong(text);require(v<=max&&(signed||v>=0),"Manifest numeric bound exceeded");return v;
        }
        void verify(MetadataProbe.Identity actual) {
            actual.requireRuntime();
            verify(actual.main,"main"); verify(actual.test,"test");
            require(actual.main.certificateDerHex.equals(actual.test.certificateDerHex),"MAIN/TEST complete DER differs");
        }
        void verify(MetadataProbe.PackageIdentity a,String prefix) {
            require(values.get(prefix+"ApkSha256").equals(a.apkSha256)&&Long.parseLong(values.get(prefix+"ApkBytes"))==a.apkBytes,"Complete code APK differs");
            long bytes=Long.parseLong(values.get("certificateBytes"));
            require(!a.multipleSigners&&a.signerCount==1&&bytes==a.certificateBytes&&values.get("certificateSha256").equals(a.certificateSha256)
                    &&a.certificateDerHex!=null&&a.certificateDerHex.matches("[0-9a-f]+")&&bytes<=Integer.MAX_VALUE/2&&a.certificateDerHex.length()==bytes*2,"Complete signer differs");
        }
    }
    private static final class ExpectedLeaf {
        final Stat stat;final String sha;final boolean copy;
        ExpectedLeaf(Stat stat,String sha,boolean copy){this.stat=stat;this.sha=sha;this.copy=copy;}
    }
    public static final String FILES="/storage/emulated/0/Android/data/"+MetadataProbe.MAIN+"/files";
    public static final String ROOT=FILES+"/runs";
    public static final String LEAF="client-diagnostic.jsonl";
    public static final int CHUNK_BYTES=8192, FRAME_CHARS=8192;
    public static final long MAX_LEAF=16L*1024*1024,MAX_TOTAL=64L*1024*1024,WINDOW_NANOS=30_000_000_000L;
    public static final class PlatformFailure extends Exception {
        public final int errno;
        public PlatformFailure(int errno,Throwable cause) {super(cause==null?"OS failure":cause.getMessage(),cause);this.errno=errno;}
    }
    private static final class DeliveryStop extends Exception { }
    private static final class Leaf {
        final String run,path;final Stat stat;String sha;
        Leaf(String run,String path,Stat stat){this.run=run;this.path=path;this.stat=stat;}
    }
    public static Result run(Access access, Transport transport, Map<String,String> binding) {
        return new Execution(access,transport).execute(binding);
    }
    private static final class Execution {
        final Access a;final Transport t;final Result r=new Result();
        final Map<String,Stat> observed=new LinkedHashMap<String,Stat>();
        final List<String> runs=new ArrayList<String>();final List<Leaf> leaves=new ArrayList<Leaf>();
        MessageDigest stream;String stage="clock-start",path;
        Execution(Access a,Transport t){this.a=a;this.t=t;}
        Result execute(Map<String,String> binding) {
            try {
                r.startUtcMillis=a.utcMillis();r.startMonotonicNanos=a.monotonicNanos();
                stream=MessageDigest.getInstance("SHA-256");
                stage="binding";r.request=new Request(binding);
                stage="identity";a.observeIdentity(r.identity);r.request.verify(r.identity);budget();
                event("identity",OwnRunsJson.object("actual",OwnRunsJson.identity(r.identity)));
                if(chain()) {
                    inventory();
                    if("backup".equals(r.request.mode))validateAccepted();
                    for(Leaf leaf:leaves) {
                        ExpectedLeaf expected=r.request.accepted.get(leaf.run);
                        hash(leaf,expected!=null&&expected.copy);
                        if(expected!=null){stage="manifest-hash";path=leaf.path;require(expected.sha.equals(leaf.sha),"Accepted content hash differs");}
                    }
                    recheck();r.manifest=manifest();
                } else {require(!"backup".equals(r.request.mode)||r.request.accepted.isEmpty(),"Accepted runs disappeared");r.manifest="e9-ownruns-manifest-v1\n";}
                budget();r.completed=true;
            } catch(DeliveryStop stop) {
                // Delivery errors remain independent of the first OS/validation failure.
            } catch(Throwable failure) {
                r.primaryFailure=failure;r.primaryStage=stage;r.primaryPath=path;
                if(failure instanceof PlatformFailure)r.primaryErrno=((PlatformFailure)failure).errno;
            } finally {
                try {r.endUtcMillis=a.utcMillis();r.endMonotonicNanos=a.monotonicNanos();
                    if(r.startMonotonicNanos>r.endMonotonicNanos||r.endMonotonicNanos-r.startMonotonicNanos>=WINDOW_NANOS)
                        throw new IllegalStateException("Observation deadline exceeded or monotonic clock reversed");
                } catch(Throwable failure) {if(r.primaryFailure==null){r.primaryFailure=failure;r.primaryStage="clock-end";r.primaryPath=null;}else r.secondaryFailures.add(failure);}
                if(r.primaryFailure!=null&&stream!=null) {
                    try {event("failure",OwnRunsJson.object("stage",r.primaryStage,"path",r.primaryPath,"errno",r.primaryErrno,"error",OwnRunsJson.error(r.primaryFailure)));}
                    catch(Throwable ignored) { /* event records its transport failure before throwing */ }
                }
                if(stream!=null)r.eventSha256=hex(stream.digest());
                try {t.finish(r);}catch(Throwable failure){r.transportFailures.add(failure);}
            }
            return r;
        }
        void budget() {
            long now=a.monotonicNanos();
            require(now>=r.startMonotonicNanos&&now-r.startMonotonicNanos<WINDOW_NANOS,"Observation deadline exceeded or clock reversed");
        }
        Stat stat(String p) throws Exception {
            budget();stage="lstat";path=p;Stat s=a.lstat(p);
            require(s!=null&&s.mode>=0&&s.uid>=0&&s.gid>=0&&s.device>=0&&s.inode>=0&&s.size>=0,"Invalid stat fields");
            event("stat",OwnRunsJson.object("path",p,"attrs",OwnRunsJson.stat(s)));return s;
        }
        void directory(String p) throws Exception {
            Stat s=stat(p);require((s.mode&0170000)==0040000,"Expected ordinary directory");observed.put(p,s);
        }
        boolean chain() throws Exception {
            directory("/storage");directory("/storage/self");
            Stat link=stat("/storage/self/primary");require((link.mode&0170000)==0120000,"Expected observed primary symlink");observed.put(path,link);
            checkLink();
            for(String p:Arrays.asList("/storage/emulated","/storage/emulated/0","/storage/emulated/0/Android",
                    "/storage/emulated/0/Android/data","/storage/emulated/0/Android/data/"+MetadataProbe.MAIN,FILES,ROOT)) {
                try {directory(p);} catch(PlatformFailure failure) {
                    if(failure.errno!=2||!(p.equals(FILES)||p.equals(ROOT)))throw failure;
                    String parent=p.substring(0,p.lastIndexOf('/')),name=p.substring(p.lastIndexOf('/')+1);
                    Stat before=observed.get(parent);require(before!=null,"Missing own parent");
                    int matches=directedMatches(parent,name);
                    require(matches==0,"Absent stat conflicts with successful own-parent names");same(before,stat(parent));
                    event("absent",OwnRunsJson.object("path",p,"provenByOwnParentNames",true));recheck();return false;
                }
            }return true;
        }
        void checkLink() throws Exception {
            budget();stage="readlink";path="/storage/self/primary";String text=a.readlink(path);
            event("readlink",OwnRunsJson.object("path",path,"text",text));require("/storage/emulated/0".equals(text),"Primary literal changed");
        }
        String[] names(String p,String purpose) throws Exception {
            budget();stage="names";path=p;String[] names=a.list(p);
            event("names",OwnRunsJson.object("path",p,"purpose",purpose,"javaStrings",names==null?null:Arrays.asList(names)));
            require(names!=null,"File.list returned null; not absence");return names;
        }
        int directedMatches(String parent,String target) throws Exception {
            budget();stage="directed-names";path=parent;int matches=0;
            try {
                String[] values=a.list(parent);
                require(values!=null,"Directed File.list returned null; not absence");
                HashSet<String> unique=new HashSet<String>();
                for(String value:values) {
                    require(validComponent(value)&&unique.add(value),"Invalid or duplicate directed parent component");
                    if(target.equals(value))matches++;
                }
            } catch(Exception failure){directedFailure(target);throw failure;}
              catch(Error failure){directedFailure(target);throw failure;}
            event("directed-query",OwnRunsJson.object("targetBasename",target,"matchCount",matches,"querySuccessful",true));
            return matches;
        }
        void directedFailure(String target) {
            try{event("directed-query",OwnRunsJson.object("targetBasename",target,"matchCount",null,"querySuccessful",false));}
            catch(DeliveryStop ignored){ /* transport failure is separate; preserve validation/OS first cause */ }
        }
        void inventory() throws Exception {
            String[] names=names(ROOT,"runs");same(observed.get(ROOT),stat(ROOT));
            stage="names-validate";path=ROOT;
            require(names.length<=128,"Run count observation budget exceeded");
            HashSet<String> unique=new HashSet<String>();
            for(String name:names){require(validRun(name)&&unique.add(name),"Unknown, duplicate or invalid run name");runs.add(name);}
            for(String name:runs) {
                String dir=ROOT+"/"+name;directory(dir);
                String[] children=names(dir,"run-leaves");same(observed.get(dir),stat(dir));
                stage="names-validate";path=dir;
                require(children.length<=1,"Unknown or duplicate run leaf names");
                if(children.length==1) {
                    require(LEAF.equals(children[0]),"Unknown or invalid diagnostic leaf name");
                    String p=dir+"/"+LEAF;Stat s=stat(p);require((s.mode&0170000)==0100000,"Expected ordinary diagnostic leaf");
                    require(s.size<=MAX_LEAF,"Per-leaf observation byte budget exceeded");
                    leaves.add(new Leaf(name,p,s));observed.put(p,s);
                }
            }
            long total=0;for(Leaf l:leaves)total=Math.addExact(total,l.stat.size);
            require(total<=MAX_TOTAL,"Total observation byte budget exceeded");
        }
        void hash(Leaf l,boolean copy) throws Exception {
            same(l.stat,stat(l.path));OpenFile f=null;Throwable first=null;String savedStage=null,savedPath=null;
            try {
                budget();stage="open";path=l.path;f=a.open(l.path);require(f!=null,"Missing read-only file handle");
                stage="fstat-open";descriptorStat(l,f);
                MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buf=new byte[CHUNK_BYTES];long offset=0;
                while(true) {
                    budget();stage="read";int count=f.read(buf);
                    require(count>=-1&&count<=buf.length&&count!=0,"Invalid read count");
                    if(count==-1)break;
                    require(offset+count<=l.stat.size&&offset+count<=MAX_LEAF&&r.readBytes+count<=MAX_TOTAL,"Read growth or byte budget exceeded");
                    digest.update(buf,0,count);
                    if(copy)event("content",OwnRunsJson.object("run",l.run,"offset",offset,"base64",Base64.getEncoder().encodeToString(Arrays.copyOf(buf,count))));
                    offset+=count;r.readBytes+=count;
                }
                require(offset==l.stat.size,"Early EOF differs from observed size");
                stage="fstat-eof";descriptorStat(l,f);
                l.sha=hex(digest.digest());r.leafHashes.put(l.run,l.sha);
                event("hash",OwnRunsJson.object("run",l.run,"bytes",offset,"sha256",l.sha));
            } catch(Exception failure){first=failure;savedStage=stage;savedPath=path;throw failure;}
              catch(Error failure){first=failure;savedStage=stage;savedPath=path;throw failure;}
            finally {
                if(f!=null)try{stage="close";f.close();}catch(Throwable close){if(first!=null)r.secondaryFailures.add(close);else if(close instanceof Exception)throw (Exception)close;else throw (Error)close;}
                if(first!=null){stage=savedStage;path=savedPath;}
            }
            same(l.stat,stat(l.path));
        }
        void descriptorStat(Leaf leaf,OpenFile file) throws Exception {
            Stat s=file.fstat();
            event("fstat",OwnRunsJson.object("path",leaf.path,"phase",stage,"attrs",s==null?null:OwnRunsJson.stat(s)));
            same(leaf.stat,s);
        }
        void recheck() throws Exception {
            for(Map.Entry<String,Stat> e:observed.entrySet())same(e.getValue(),stat(e.getKey()));checkLink();
        }
        void validateAccepted() {
            stage="manifest-match";path=ROOT;
            require(new HashSet<String>(runs).equals(r.request.accepted.keySet()),"Accepted complete run set differs");
            for(String run:runs) {
                ExpectedLeaf expected=r.request.accepted.get(run);Leaf actual=null;
                for(Leaf l:leaves)if(l.run.equals(run))actual=l;
                require((expected==null)==(actual==null),"Accepted leaf presence differs");
                if(actual!=null)same(expected.stat,actual.stat);
            }
        }
        String manifest() {
            StringBuilder b=new StringBuilder("e9-ownruns-manifest-v1\n");
            for(String run:runs) {
                Leaf leaf=null;for(Leaf l:leaves)if(l.run.equals(run))leaf=l;
                b.append(run).append('\t');
                if(leaf==null)b.append('-');else {
                    Stat s=leaf.stat;b.append(s.mode).append('\t').append(s.uid).append('\t').append(s.gid).append('\t').append(s.device).append('\t').append(s.inode).append('\t').append(s.size).append('\t').append(s.modifiedSeconds).append('\t').append(s.changedSeconds).append('\t').append(leaf.sha).append("\t0");
                }b.append('\n');
            }require(b.length()<=65536,"Inventory manifest observation budget exceeded");return b.toString();
        }
        void event(String type,Map<String,Object> data) throws DeliveryStop {
            data.put("type",type);String text=OwnRunsJson.encode(data);
            try {
                frames(t,r.eventCount,"event",text);
                stream.update(text.getBytes(StandardCharsets.US_ASCII));stream.update((byte)10);r.eventCount++;
            } catch(Throwable failure){r.transportFailures.add(failure);throw new DeliveryStop();}
        }
    }
    public static void frames(Transport t,long sequence,String kind,String text) throws Exception {
        int part=0;for(int offset=0;offset<text.length();offset+=FRAME_CHARS)
            t.record(sequence,kind,part++,offset+FRAME_CHARS>=text.length(),text.substring(offset,Math.min(text.length(),offset+FRAME_CHARS)));
    }
    public static boolean validRun(String name) {return name!=null&&name.matches("run-[0-9]+-[0-9a-f]{8}");}
    private static boolean validComponent(String name) {
        if(name==null||name.length()==0||name.equals(".")||name.equals(".."))return false;
        for(int i=0;i<name.length();i++) {
            char c=name.charAt(i);
            if(c=='/'||c=='\\'||Character.isISOControl(c))return false;
            if(Character.isHighSurrogate(c)) {
                if(i+1>=name.length()||!Character.isLowSurrogate(name.charAt(i+1)))return false;
                i++;
            } else if(Character.isLowSurrogate(c))return false;
        }return true;
    }
    public static boolean allowedStatPath(String p) {
        if(Arrays.asList("/storage","/storage/self","/storage/self/primary","/storage/emulated","/storage/emulated/0",
                "/storage/emulated/0/Android","/storage/emulated/0/Android/data","/storage/emulated/0/Android/data/"+MetadataProbe.MAIN,FILES,ROOT).contains(p))return true;
        if(!p.startsWith(ROOT+"/"))return false;String tail=p.substring(ROOT.length()+1);int slash=tail.indexOf('/');
        return slash<0?validRun(tail):validRun(tail.substring(0,slash))&&LEAF.equals(tail.substring(slash+1));
    }
    public static void same(Stat a,Stat b) {
        require(a!=null&&b!=null&&a.mode==b.mode&&a.uid==b.uid&&a.gid==b.gid&&a.device==b.device&&a.inode==b.inode&&a.size==b.size&&a.modifiedSeconds==b.modifiedSeconds&&a.changedSeconds==b.changedSeconds,"Observed object attributes changed");
    }
    public static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte x:bytes){int v=x&255;if(v<16)b.append('0');b.append(Integer.toHexString(v));}return b.toString();}
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
}
