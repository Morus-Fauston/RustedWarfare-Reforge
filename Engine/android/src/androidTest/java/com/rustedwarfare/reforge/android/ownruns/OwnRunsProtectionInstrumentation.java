package com.rustedwarfare.reforge.android.ownruns;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import com.rustedwarfare.reforge.android.metadata.MetadataProbe;

/** TEST-only reader. Normal framework binding; no business Activity, getters or device writes. */
public final class OwnRunsProtectionInstrumentation extends Instrumentation {
    private Bundle arguments;
    private String bindingId="";
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);this.arguments=arguments;start();
    }
    @Override public void onStart() {
        final Map<String,String> request=new LinkedHashMap<String,String>();
        Throwable bad=null;
        try {
            if(arguments!=null)for(String key:arguments.keySet()) {
                Object value=arguments.get(key);
                if(!(value instanceof String))throw new IllegalArgumentException("Request values must be strings: "+key);
                request.put(key,(String)value);
            }
            if(request.get("bindingId")!=null)bindingId=request.get("bindingId");
        } catch(Throwable failure) {bad=failure;}
        final Throwable badRequest=bad;
        OwnRunsProbe.run(new AndroidAccess(badRequest),new OwnRunsProbe.Transport() {
            @Override public void record(long sequence,String kind,int part,boolean last,String payload) {
                if(payload.length()>OwnRunsProbe.FRAME_CHARS)throw new IllegalArgumentException("Oversized frame");
                Bundle frame=new Bundle();frame.putString("schema","e9-ownruns-frame-v1");frame.putString("bindingId",bindingId);
                frame.putString("sequence",Long.toString(sequence));frame.putString("kind",kind);frame.putString("part",Integer.toString(part));
                frame.putString("last",Boolean.toString(last));frame.putString("payload",payload);sendStatus(1,frame);
            }
            @Override public void finish(OwnRunsProbe.Result result) throws Exception {
                String summary=result.toJson();int parts=(summary.length()+OwnRunsProbe.FRAME_CHARS-1)/OwnRunsProbe.FRAME_CHARS;
                String sha=hex(MessageDigest.getInstance("SHA-256").digest(summary.getBytes(StandardCharsets.US_ASCII)));
                Bundle terminal=new Bundle();terminal.putString("schema","e9-ownruns-terminal-v1");terminal.putString("bindingId",bindingId);
                terminal.putString("summaryParts",Integer.toString(parts));terminal.putString("summarySha256",sha);
                terminal.putString("eventCount",Long.toString(result.eventCount));terminal.putString("eventSha256",result.eventSha256);
                try {OwnRunsProbe.frames(this,result.eventCount,"summary",summary);}
                catch(Exception failure) {
                    terminal.putString("success","false");terminal.putString("summaryTransmissionFailed","true");
                    OwnRunsProtectionInstrumentation.this.finish(Activity.RESULT_CANCELED,terminal);throw failure;
                }
                terminal.putString("success",Boolean.toString(result.successful()));
                OwnRunsProtectionInstrumentation.this.finish(result.successful()?Activity.RESULT_OK:Activity.RESULT_CANCELED,terminal);
            }
        },request);
    }
    private final class AndroidAccess implements OwnRunsProbe.Access {
        final Throwable badRequest;
        AndroidAccess(Throwable badRequest){this.badRequest=badRequest;}
        @Override public void observeIdentity(MetadataProbe.Identity actual) throws Exception {
            if (badRequest != null) throw new IllegalArgumentException("Non-string request rejected", badRequest);
            actual.processUid = Process.myUid();
            actual.processPid = Process.myPid();
            actual.processUser0 = Process.myUserHandle().equals(UserHandle.getUserHandleForUid(0));
            actual.sdkInt = Build.VERSION.SDK_INT;
            if (actual.sdkInt < 28) throw new IllegalStateException("Public process-name/signing APIs require API28+");
            actual.processName = Application.getProcessName();
            PackageInfo main = observePackage(getTargetContext(), MetadataProbe.MAIN, actual.main);
            PackageInfo test = observePackage(getContext(), MetadataProbe.TEST, actual.test);
            // Reject malformed Context/PM identity before the explicit, code-APK-only content exception.
            actual.requireRuntime();
            observeCode(main, actual.main);
            observeCode(test, actual.test);
        }

        private PackageInfo observePackage(Context context, String fixedName, MetadataProbe.PackageIdentity actual)
                throws PackageManager.NameNotFoundException {
            if (context == null) throw new IllegalStateException("Missing instrumentation Context");
            actual.contextPackage = context.getPackageName();
            ApplicationInfo contextInfo = context.getApplicationInfo();
            if (contextInfo == null) throw new IllegalStateException("Missing Context ApplicationInfo");
            actual.contextUid = contextInfo.uid;
            PackageInfo info = context.getPackageManager().getPackageInfo(fixedName, PackageManager.GET_SIGNING_CERTIFICATES);
            actual.packageName = info.packageName;
            ApplicationInfo app = info.applicationInfo;
            if (app == null) throw new IllegalStateException("Missing PM ApplicationInfo");
            actual.uid = app.uid;
            actual.processName = app.processName;
            actual.sourceDir = app.sourceDir;
            actual.publicSourceDir = app.publicSourceDir;
            actual.splitCount = Math.max(info.splitNames == null ? 0 : info.splitNames.length,
                    Math.max(app.splitSourceDirs == null ? 0 : app.splitSourceDirs.length,
                            app.splitPublicSourceDirs == null ? 0 : app.splitPublicSourceDirs.length));
            return info;
        }

        private void observeCode(PackageInfo info, MetadataProbe.PackageIdentity actual) throws Exception {
            if (info.signingInfo == null) throw new IllegalStateException("Missing PM SigningInfo");
            actual.multipleSigners = info.signingInfo.hasMultipleSigners();
            Signature[] signers = info.signingInfo.getApkContentsSigners();
            actual.signerCount = signers == null ? 0 : signers.length;
            if (actual.multipleSigners || actual.signerCount != 1) throw new IllegalStateException("Expected exactly one current signer");
            byte[] der = signers[0].toByteArray();
            actual.certificateBytes = der.length;
            actual.certificateDerHex = hex(der);
            actual.certificateSha256 = hex(MessageDigest.getInstance("SHA-256").digest(der));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // This is the validated PackageManager base code APK, never a requested or returned data path.
            try (FileInputStream input = new FileInputStream(actual.sourceDir)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                    actual.apkBytes = Math.addExact(actual.apkBytes, count);
                }
            }
            actual.apkSha256 = hex(digest.digest());
        }

        @Override public OwnRunsProbe.Stat lstat(String path) throws Exception {
            if(!OwnRunsProbe.allowedStatPath(path))throw new IllegalArgumentException("Not a fixed ownruns metadata path");
            try{return convert(Os.lstat(path));}catch(ErrnoException e){throw new OwnRunsProbe.PlatformFailure(e.errno,e);}
        }
        @Override public String readlink(String path) throws Exception {
            if(!"/storage/self/primary".equals(path))throw new IllegalArgumentException("Not the fixed primary link");
            try{return Os.readlink(path);}catch(ErrnoException e){throw new OwnRunsProbe.PlatformFailure(e.errno,e);}
        }
        @Override public String[] list(String path) {
            boolean allowed=path.equals(OwnRunsProbe.ROOT)||path.equals(OwnRunsProbe.FILES)||path.equals(OwnRunsProbe.FILES.substring(0,OwnRunsProbe.FILES.lastIndexOf('/')));
            if(path.startsWith(OwnRunsProbe.ROOT+"/"))allowed=OwnRunsProbe.validRun(path.substring(OwnRunsProbe.ROOT.length()+1));
            if(!allowed)throw new IllegalArgumentException("Not a fixed own-parent/run names path");
            return new File(path).list();
        }
        @Override public OwnRunsProbe.OpenFile open(String path) throws Exception {
            if(!path.startsWith(OwnRunsProbe.ROOT+"/")||!path.endsWith("/"+OwnRunsProbe.LEAF)||!OwnRunsProbe.allowedStatPath(path))
                throw new IllegalArgumentException("Not a known fixed diagnostic leaf");
            final FileDescriptor fd;
            try{fd=Os.open(path,OsConstants.O_RDONLY|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);}
            catch(ErrnoException e){throw new OwnRunsProbe.PlatformFailure(e.errno,e);}
            return new OwnRunsProbe.OpenFile() {
                @Override public OwnRunsProbe.Stat fstat() throws Exception {
                    try{return convert(Os.fstat(fd));}catch(ErrnoException e){throw new OwnRunsProbe.PlatformFailure(e.errno,e);}
                }
                @Override public int read(byte[] bytes) throws Exception {
                    try{int count=Os.read(fd,bytes,0,bytes.length);return count==0?-1:count;}
                    catch(ErrnoException e){throw new OwnRunsProbe.PlatformFailure(e.errno,e);}
                }
                @Override public void close() throws Exception {
                    try{Os.close(fd);}catch(ErrnoException e){throw new OwnRunsProbe.PlatformFailure(e.errno,e);}
                }
            };
        }
        @Override public long utcMillis(){return System.currentTimeMillis();}
        @Override public long monotonicNanos(){return SystemClock.elapsedRealtimeNanos();}
    }
    private static OwnRunsProbe.Stat convert(StructStat s) {
        return new OwnRunsProbe.Stat(s.st_mode,s.st_uid,s.st_gid,s.st_dev,s.st_ino,s.st_size,s.st_mtime,s.st_ctime,s.st_atime);
    }
    private static String hex(byte[] bytes){return OwnRunsProbe.hex(bytes);}
}
