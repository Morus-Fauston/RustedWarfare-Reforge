package com.rustedwarfare.reforge.android.ownruns;

/** Regression tests for E9-SRC-01/02 through the already agreed run/access/transport seam. */
public final class OwnRunsDirectedAbsenceHostTest {
    private static int checks;
    private static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    private static final String OTHER="private-sibling-marker";
    private static final class Directed extends OwnRunsProbeHostTest.Host {
        final String missing,parent;final String[] names;
        int queries;boolean throwQuery,drift;int parentStats;
        Directed(String missing,String[] names){this.missing=missing;this.parent=missing.substring(0,missing.lastIndexOf('/'));this.names=names;}
        @Override public OwnRunsProbe.Stat lstat(String p)throws Exception {
            if(p.equals(missing))throw new OwnRunsProbe.PlatformFailure(2,new java.io.IOException("fixture missing fixed own component"));
            OwnRunsProbe.Stat s=super.lstat(p);
            if(p.equals(parent)&&drift&&++parentStats>1)return new OwnRunsProbe.Stat(s.mode,s.uid,s.gid,s.device,s.inode+1,s.size,s.modifiedSeconds,s.changedSeconds,s.accessedSeconds);
            return s;
        }
        @Override public String[] list(String p)throws Exception {
            if(!p.equals(parent))throw new AssertionError("Directed query escaped its verified own parent");queries++;
            if(throwQuery)throw new OwnRunsProbe.PlatformFailure(13,new java.io.IOException("injected own-parent names refused"));
            return names;
        }
    }
    private static OwnRunsProbe.Result run(Directed h){return OwnRunsProbeHostTest.run(h);}
    private static String output(Directed h,OwnRunsProbe.Result r){return OwnRunsProbeHostTest.joined(h)+r.toJson();}
    private static boolean absent(String output){return output.contains("\"type\":\"absent\"");}
    private static void privacy() {
        for(String missing:new String[]{OwnRunsProbe.FILES,OwnRunsProbe.ROOT}) {
            Directed h=new Directed(missing,new String[]{OTHER,"valid-other-name","valid-\uD83D\uDE00"});OwnRunsProbe.Result r=run(h);
            String text=output(h,r);
            check(r.successful()&&h.opens==0&&h.queries==1,"valid non-target names prove fixed own absence with zero content reads");
            check(!text.contains(OTHER)&&!text.contains("valid-other-name")&&!text.contains("valid-\\ud83d"),"directed absence must never transmit other parent basenames");
            check(absent(text)&&text.contains("\"targetBasename\":\""+(missing.equals(OwnRunsProbe.FILES)?"files":"runs")+"\"")&&text.contains("\"matchCount\":0")&&text.contains("\"querySuccessful\":true"),"only fixed basename/match count/success facts support absence");
            check(!text.contains("\"javaStrings\""),"directed query does not enter generic raw names transport");
        }
    }
    private static void malformed() {
        for(String[] bad:new String[][]{{null},{OTHER,OTHER},{""},{"."},{".."},{"a/b"},{"a\\b"},{"a\u0000b"},{"a\nb"},{"a\u007fb"},{"a\u0085b"},{"a\ud800b"},{"a\udc00b"},{"a\ud800"},{"a\udc00"}}) {
            Directed h=new Directed(OwnRunsProbe.ROOT,bad);OwnRunsProbe.Result r=run(h);String text=output(h,r);
            check(!r.successful()&&h.opens==0&&!absent(text),"malformed parent array must not prove absence");
            check(!text.contains(OTHER)&&!text.contains("\"javaStrings\""),"malformed non-target names are never transmitted");
            check(text.contains("\"querySuccessful\":false")&&text.contains("\"matchCount\":null"),"failed directed validation retains failure facts without a count");
        }
    }
    private static void boundaries() {
        Directed h=new Directed(OwnRunsProbe.ROOT,null);OwnRunsProbe.Result r=run(h);
        check(!r.successful()&&h.opens==0&&!absent(output(h,r)),"whole null array remains failure, existing behavior preserved");
        h=new Directed(OwnRunsProbe.ROOT,new String[]{OTHER,"runs"});r=run(h);String text=output(h,r);
        check(!r.successful()&&h.opens==0&&!absent(text)&&text.contains("\"matchCount\":1")&&!text.contains(OTHER),"exact target conflicts with ENOENT and cannot prove absence");
        h=new Directed(OwnRunsProbe.ROOT,new String[]{"runs","runs"});r=run(h);
        check(!r.successful()&&!absent(output(h,r)),"duplicate targets fail full array validation");
        h=new Directed(OwnRunsProbe.ROOT,new String[0]);r=run(h);
        check(r.successful()&&h.opens==0&&absent(output(h,r)),"successful empty array proves fixed absence");
        h=new Directed(OwnRunsProbe.ROOT,new String[]{OTHER});h.throwQuery=true;r=run(h);
        check(!r.successful()&&r.primaryErrno==13&&h.opens==0&&!absent(output(h,r)),"query errno stays first cause and cannot prove absence");
        h=new Directed(OwnRunsProbe.ROOT,new String[]{OTHER});h.drift=true;r=run(h);
        check(!r.successful()&&h.opens==0&&!absent(output(h,r)),"parent object drift prevents absence acceptance");
    }
    public static void main(String[] args) {
        if(args[0].equals("privacy")||args[0].equals("all"))privacy();
        if(args[0].equals("malformed")||args[0].equals("all"))malformed();
        if(args[0].equals("all"))boundaries();
        System.out.println("PASS "+checks+" directed-absence checks");
    }
}
