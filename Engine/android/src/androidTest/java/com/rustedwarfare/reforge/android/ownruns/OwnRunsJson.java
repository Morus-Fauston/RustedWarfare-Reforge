package com.rustedwarfare.reforge.android.ownruns;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.io.PrintWriter;
import java.io.StringWriter;
import com.rustedwarfare.reforge.android.metadata.MetadataProbe;

/** ASCII JSON preserves every returned UTF-16 code unit, including invalid names. */
public final class OwnRunsJson {
    private OwnRunsJson() { }
    public static Map<String,Object> object(Object... fields) {
        Map<String,Object> m=new LinkedHashMap<String,Object>();
        for(int i=0;i<fields.length;i+=2)m.put((String)fields[i],fields[i+1]);return m;
    }
    public static String encode(Object value) {StringBuilder b=new StringBuilder();append(b,value);return b.toString();}
    private static void append(StringBuilder b,Object v) {
        if(v==null)b.append("null");
        else if(v instanceof String) {
            b.append('"');String s=(String)v;
            for(int i=0;i<s.length();i++) {
                char c=s.charAt(i);
                if(c=='"'||c=='\\')b.append('\\').append(c);
                else if(c<32||c>126) {b.append("\\u");String h=Integer.toHexString(c);for(int j=h.length();j<4;j++)b.append('0');b.append(h);}
                else b.append(c);
            }b.append('"');
        } else if(v instanceof Boolean||v instanceof Number)b.append(v);
        else if(v instanceof Map) {
            b.append('{');boolean first=true;
            for(Map.Entry<?,?>e:((Map<?,?>)v).entrySet()){if(!first)b.append(',');first=false;append(b,e.getKey());b.append(':');append(b,e.getValue());}b.append('}');
        } else if(v instanceof Iterable) {
            b.append('[');boolean first=true;for(Object x:(Iterable<?>)v){if(!first)b.append(',');first=false;append(b,x);}b.append(']');
        } else throw new IllegalArgumentException("Unsupported JSON value");
    }
    static Map<String,Object> error(Throwable t) {
        if(t==null)return null;StringWriter s=new StringWriter();t.printStackTrace(new PrintWriter(s));
        return object("type",t.getClass().getName(),"message",t.getMessage(),"stackTrace",s.toString());
    }
    static Map<String,Object> stat(OwnRunsProbe.Stat s) {
        return object("mode",s.mode,"uid",s.uid,"gid",s.gid,"device",s.device,"inode",s.inode,"size",s.size,
                "modifiedSeconds",s.modifiedSeconds,"changedSeconds",s.changedSeconds,"accessedSeconds",s.accessedSeconds);
    }
    static Map<String,Object> identity(MetadataProbe.Identity i) {
        return object("processUid",i.processUid,"processPid",i.processPid,"processName",i.processName,"processUser0",i.processUser0,
                "sdkInt",i.sdkInt,"main",pkg(i.main),"test",pkg(i.test));
    }
    private static Map<String,Object> pkg(MetadataProbe.PackageIdentity p) {
        return object("contextPackage",p.contextPackage,"contextUid",p.contextUid,"packageName",p.packageName,"uid",p.uid,
                "processName",p.processName,"sourceDir",p.sourceDir,"publicSourceDir",p.publicSourceDir,"splitCount",p.splitCount,
                "signerCount",p.signerCount,"multipleSigners",p.multipleSigners,"apkSha256",p.apkSha256,"apkBytes",p.apkBytes,
                "certificateSha256",p.certificateSha256,"certificateBytes",p.certificateBytes,"certificateDerHex",p.certificateDerHex);
    }
}
