package com.rustedwarfare.reforge.reference.instrumentation;
import java.util.LinkedHashMap;
import java.util.Map;
public final class MotionProfilesTest {
    public static void main(String[] args){
        for(String mode:new String[]{"on","off","bare","fault"}){
            Map<String,String> request=new LinkedHashMap<String,String>();
            request.put("schemaVersion","1");request.put("profile","r21.p001.normal-motion."+mode+".v1");request.put("requestId","host-profile");
            request.put("runId","00000000-0000-0000-0000-000000000001");request.put("targetApkSha256",mode.equals("bare")?InitialProtocol.TARGET_HASH:InitialProtocol.MOTION_TARGET_HASH);
            request.put("testApkSha256",InitialProtocol.SIGNER_HASH);request.put("signerSha256",InitialProtocol.SIGNER_HASH);
            InitialProtocol.validate(request);if(!mode.equals(InitialProtocol.motionMode(request)))throw new AssertionError();
            request.put("targetApkSha256",mode.equals("bare")?InitialProtocol.MOTION_TARGET_HASH:InitialProtocol.TARGET_HASH);
            boolean rejected=false;try{InitialProtocol.validate(request);}catch(IllegalArgumentException e){rejected=true;}if(!rejected)throw new AssertionError("mode target binding");
        }
        System.out.println("PASS four fixed motion profiles and target mismatch rejection");
    }
}
