package com.rustedwarfare.reforge.reference.instrumentation;

import android.content.Context;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Fixed test-APK map bytes, exclusive per-run private leaf; no original seed overwrite. */
final class SceneInputs {
    static String prepare(Path files,Path root,String runId,ScenePlan plan,byte[] bytes) throws Exception {
        InitialProtocol.require(files.isAbsolute() && root.isAbsolute() && files.toRealPath().equals(files)
            && root.equals(files.resolve("reference-test")),"scene-private-root");
        InitialProtocol.require(UUID.fromString(runId).toString().equals(runId),"scene-run-uuid");
        String hash=SceneMaps.hash(plan.id);
        InitialProtocol.require(bytes!=null && bytes.length>0 && bytes.length<=65536
            && hash.equals(InitialProtocol.sha256(bytes)),"scene-map-input-identity");
        // The frozen bootstrap admits only its one seed in maps/. Keep added scenes in the existing bridge leaf.
        ReadbackFiles.fixedParents(files,root.resolve("bridge"),new ArrayList<Object>());
        Path target=root.resolve("bridge").resolve("o-"+runId.replace("-", "")+"-"+plan.id+".tmx");
        Files.write(target,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        verify(target.toString(),plan);return target.toString();
    }
    static String prepare(Context testContext,Path files,Path root,String runId,ScenePlan plan) throws Exception {
        InitialProtocol.require("com.rustedwarfare.reforge.reference.test".equals(testContext.getPackageName()),"scene-test-asset-context");
        byte[] buffer=new byte[4096];ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(InputStream input=testContext.getAssets().open("s07-scenes/"+plan.id+".tmx")) {
            int count;while((count=input.read(buffer))!=-1){
                InitialProtocol.require(output.size()+count<=65536,"scene-asset-size");output.write(buffer,0,count);
            }
        }
        return prepare(files,root,runId,plan,output.toByteArray());
    }
    static Map<String,Object> verify(String path,ScenePlan plan) throws Exception {
        Map<String,Object> row=new LinkedHashMap<String,Object>();
        ReadbackFiles.readFile(Paths.get(path),row,null,SceneMaps.hash(plan.id));return row;
    }
}
