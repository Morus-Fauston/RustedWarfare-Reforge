package com.rustedwarfare.reforge.reference.io;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class ReferenceIoTest {
    private static int checks;
    private static void equal(Object expected,Object actual) {checks++;if(!expected.equals(actual))throw new AssertionError("expected="+expected+" actual="+actual);}
    private interface Checked {void run() throws Exception;}
    private static FatalLatch.Failure denied(String code,Checked call) throws Exception {
        checks++;try {call.run();}catch(FatalLatch.Failure error){equal(code,error.code());return error;}
        throw new AssertionError("Expected failure "+code);
    }
    public static void main(String[] args) throws Exception {
        Path parent=Paths.get(args[0]).toAbsolutePath();
        Path root=Files.createDirectory(parent.resolve("route-"+java.util.UUID.randomUUID()));
        Path bridge=Files.createDirectory(root.resolve("bridge"));Path maps=Files.createDirectory(root.resolve("maps"));
        Path file=Files.write(maps.resolve("a.tmx"),"fixture-a".getBytes(StandardCharsets.UTF_8));
        ReferenceIo io=new ReferenceIo(root,bridge,new FatalLatch());
        Path normalized=io.privateFile(file.toString().replace('\\','/'),PrivatePathPolicy.Access.READ);
        equal(file.toRealPath(),normalized);
        equal("fixture-a",new String(Files.readAllBytes(normalized),StandardCharsets.UTF_8));
        FatalLatch failureLatch=new FatalLatch();ReferenceIo blocked=new ReferenceIo(root,bridge,failureLatch);
        FatalLatch.Failure first=denied("relative-private-path",()->blocked.privateFile("maps/a.tmx",PrivatePathPolicy.Access.READ));
        equal(first,denied("relative-private-path",()->blocked.asset("units/tank.ini",PrivatePathPolicy.Access.READ)));
        equal("units/tank.ini",io.asset("assets/units/tank.ini",PrivatePathPolicy.Access.READ));
        denied("asset-write-denied",()->new ReferenceIo(root,bridge,new FatalLatch()).asset("units/tank.ini",PrivatePathPolicy.Access.WRITE));
        for(String marker:new String[] {"content://external/file","x.[saflink]/a","/saf-virtual/a","x.rwmod/a","x.rwmod\\a"}) {
            denied("unsupported-route",()->new ReferenceIo(root,bridge,new FatalLatch()).privateFile(marker,PrivatePathPolicy.Access.READ));
            denied("unsupported-route",()->new ReferenceIo(root,bridge,new FatalLatch()).asset(marker,PrivatePathPolicy.Access.READ));
        }
        denied("invalid-path",()->new ReferenceIo(root,bridge,new FatalLatch()).privateFile(null,PrivatePathPolicy.Access.READ));
        denied("invalid-path",()->new ReferenceIo(root,bridge,new FatalLatch()).privateFile("content://x",null));
        denied("missing-path",()->new ReferenceIo(root,bridge,new FatalLatch()).privateFile(maps.resolve("absent.ini").toString().replace('\\','/'),PrivatePathPolicy.Access.READ));
        equal(bridge.toRealPath(),io.existingDirectory(bridge.toString().replace('\\','/')));
        equal(bridge.toRealPath(),io.existingDirectory(bridge.toString().replace('\\','/')));
        Path missing=maps.resolve("missing");
        denied("missing-path",()->new ReferenceIo(root,bridge,new FatalLatch()).existingDirectory(missing.toString().replace('\\','/')));
        equal(false,Files.exists(missing));
        denied("unsupported-io-operation",()->new ReferenceIo(root,bridge,new FatalLatch()).denyDelete(file.toString()));
        equal("fixture-a",new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
        Path src=Files.write(bridge.resolve("a.tmp"),"response".getBytes(StandardCharsets.UTF_8));Path dst=bridge.resolve("a.json");
        ReferenceIo.RenamePermit permit=io.prepareRename(src.toString().replace('\\','/'),dst.toString().replace('\\','/'));
        equal(src.toRealPath(),permit.source());equal(dst,permit.destination());
        if(!root.isAbsolute() || !root.getParent().equals(parent) || !root.getFileName().toString().startsWith("route-") ||
            !permit.source().equals(root.resolve("bridge/a.tmp")) || !permit.destination().equals(root.resolve("bridge/a.json"))) throw new AssertionError("Not this new fixture's exact owned move");
        System.out.println("OWNED_MOVE source="+permit.source()+" destination="+permit.destination());
        Files.move(permit.source(),permit.destination());
        equal("response",new String(Files.readAllBytes(dst),StandardCharsets.UTF_8));equal(false,Files.exists(src));
        Files.write(src,"new".getBytes(StandardCharsets.UTF_8));
        denied("destination-exists",()->new ReferenceIo(root,bridge,new FatalLatch()).prepareRename(src.toString().replace('\\','/'),dst.toString().replace('\\','/')));
        equal("response",new String(Files.readAllBytes(dst),StandardCharsets.UTF_8));
        denied("rename-scope",()->new ReferenceIo(root,bridge,new FatalLatch()).prepareRename(src.toString().replace('\\','/'),maps.resolve("new").toString().replace('\\','/')));
        denied("rename-scope",()->new ReferenceIo(root,bridge,new FatalLatch()).prepareRename(src.toString().replace('\\','/'),src.toString().replace('\\','/')));
        denied("wrong-type",()->new ReferenceIo(root,bridge,new FatalLatch()).prepareRename(bridge.toString().replace('\\','/'),dst.toString().replace('\\','/')));
        FatalLatch relativeBridge=new FatalLatch();
        FatalLatch.Failure relativeError=denied("invalid-bridge-root",()->new ReferenceIo(root,Paths.get("bridge"),relativeBridge));
        equal(relativeError,relativeBridge.firstFailure());
        FatalLatch nullBridge=new FatalLatch();
        FatalLatch.Failure nullError=denied("invalid-bridge-root",()->new ReferenceIo(root,null,nullBridge));
        equal(nullError,nullBridge.firstFailure());
        denied("invalid-root",()->new ReferenceIo(root.resolve("missing-root"),null,new FatalLatch()));
        Path sibling=Files.createDirectory(parent.resolve(root.getFileName()+"-sibling"));
        denied("outside-root",()->new ReferenceIo(root,bridge,new FatalLatch()).privateFile(sibling.toString().replace('\\','/'),PrivatePathPolicy.Access.LIST));
        denied("invalid-path",()->new ReferenceIo(root,bridge,new FatalLatch()).privateFile(root.toString().replace('\\','/')+"/maps/../a",PrivatePathPolicy.Access.READ));
        Path other=Files.createDirectory(root.resolve("other"));Path bridgeChild=Files.createDirectory(bridge.resolve("sub"));
        denied("invalid-bridge-root",()->new ReferenceIo(root,other,new FatalLatch()));
        denied("invalid-bridge-root",()->new ReferenceIo(root,root,new FatalLatch()));
        denied("rename-scope",()->new ReferenceIo(root,bridge,new FatalLatch()).prepareRename(src.toString().replace('\\','/'),bridgeChild.resolve("b").toString().replace('\\','/')));
        denied("missing-path",()->new ReferenceIo(root,bridge,new FatalLatch()).prepareRename(src.toString().replace('\\','/'),bridge.resolve("absent/b").toString().replace('\\','/')));
        boolean symbolic=false;
        try {Files.createSymbolicLink(root.resolve("link"),file);symbolic=true;}
        catch(java.io.IOException|UnsupportedOperationException|SecurityException error){System.out.println("UNVERIFIED symbolic-link creation: "+error);}
        if(symbolic)denied("symbolic-link",()->new ReferenceIo(root,bridge,new FatalLatch()).privateFile(root.resolve("link").toString().replace('\\','/'),PrivatePathPolicy.Access.READ));
        try {new ReferenceIo(root,bridge,null);throw new AssertionError("null latch");}catch(IllegalArgumentException expected){checks++;}
        FatalLatch prefailed=new FatalLatch();FatalLatch.Failure fixed=prefailed.trip("first","first",null);
        equal(fixed,denied("first",()->new ReferenceIo(root,bridge,prefailed)));
        equal(fixed,denied("first",()->new ReferenceIo(root,null,prefailed)));
        System.out.println("METADATA_PERMISSION_CONTROL=UNVERIFIED(no ACL mutation; readAttributes catches IO rather than exists=false)");
        System.out.println("PASS ReferenceIo checks="+checks+" fixture="+root);
    }
}
