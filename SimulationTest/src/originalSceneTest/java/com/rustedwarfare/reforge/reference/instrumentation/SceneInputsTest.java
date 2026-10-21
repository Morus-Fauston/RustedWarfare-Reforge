package com.rustedwarfare.reforge.reference.instrumentation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;

/** Real temporary filesystem: fixed map identity, exclusive creation and preservation. */
public final class SceneInputsTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args)throws Exception {
        Map<String,String> request=new LinkedHashMap<String,String>();
        request.put("schemaVersion","1");request.put("profile","r22.O.v1.OM07");request.put("requestId","scene-input-control");
        request.put("runId","00000000-0000-4000-8000-000000000001");
        request.put("targetApkSha256",InitialProtocol.MOTION_TARGET_HASH);
        request.put("testApkSha256",new String(new char[64]).replace('\0','a'));request.put("signerSha256",InitialProtocol.SIGNER_HASH);
        ScenePlan plan=ScenePlan.from(request);
        Path anchor=Files.createTempDirectory("reforge-scene-input-").toRealPath(),root=anchor.resolve("reference-test");
        Files.createDirectories(root.resolve("maps"));
        Files.createDirectory(root.resolve("bridge"));
        Path sentinel=root.resolve("maps/old-private-file.txt");Files.write(sentinel,new byte[]{1,2,3});
        byte[] map=Files.readAllBytes(Paths.get(args[0]));
        String prepared=SceneInputs.prepare(anchor,root,request.get("runId"),plan,map);
        check(Paths.get(prepared).startsWith(root.resolve("bridge")) && Arrays.equals(map,Files.readAllBytes(Paths.get(prepared))));
        boolean refused=false;
        try{SceneInputs.prepare(anchor,root,request.get("runId"),plan,map);}catch(Exception expected){refused=true;}
        check(refused && Arrays.equals(map,Files.readAllBytes(Paths.get(prepared))));
        byte[] changed=map.clone();changed[0]=(byte)'x';refused=false;
        try{SceneInputs.prepare(anchor,root,"00000000-0000-4000-8000-000000000002",plan,changed);}catch(Exception expected){refused=true;}
        check(refused && !Files.exists(root.resolve("bridge/o-00000000000040008000000000000002-OM07.tmx")));
        check(Arrays.equals(new byte[]{1,2,3},Files.readAllBytes(sentinel)));
        try(java.nio.file.DirectoryStream<Path> names=Files.newDirectoryStream(root.resolve("maps"))){int count=0;for(Path name:names){check(name.equals(sentinel));count++;}check(count==1);}
        System.out.println("SceneInputsTest passed: fixed bytes, duplicate and bad input refused, old data preserved");
    }
}
