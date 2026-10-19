package com.rustedwarfare.reforge.android.ownruns;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.SeekableByteChannel;
import java.nio.ByteBuffer;
import java.util.*;

/** Real Windows own-subtree fixtures; synthetic Android system prefix and identity remain explicit. */
final class OwnRunsFilesystemHostTest {
    private static final class Real extends OwnRunsProbeHostTest.Host {
        final Path root;
        Real(Path root){this.root=root;}
        Path physical(String p){return p.equals(OwnRunsProbe.ROOT)?root:root.resolve(p.substring(OwnRunsProbe.ROOT.length()+1));}
        @Override public OwnRunsProbe.Stat lstat(String p)throws Exception {
            if(!p.equals(OwnRunsProbe.ROOT)&&!p.startsWith(OwnRunsProbe.ROOT+"/"))return super.lstat(p);
            calls.add("real-stat:"+p);BasicFileAttributes a=Files.readAttributes(physical(p),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            int mode=a.isSymbolicLink()?0120777:a.isDirectory()?0040700:a.isRegularFile()?0100600:0;
            return new OwnRunsProbe.Stat(mode,0,0,1,Integer.toUnsignedLong(String.valueOf(a.fileKey()).hashCode()),a.size(),a.lastModifiedTime().toMillis()/1000,a.creationTime().toMillis()/1000,a.lastAccessTime().toMillis()/1000);
        }
        @Override public String[] list(String p) {calls.add("real-list:"+p);return physical(p).toFile().list();}
        @Override public OwnRunsProbe.OpenFile open(final String p)throws Exception {
            opens++;final SeekableByteChannel input=Files.newByteChannel(physical(p),new HashSet<OpenOption>(Arrays.<OpenOption>asList(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)));
            return new OwnRunsProbe.OpenFile(){
                public OwnRunsProbe.Stat fstat()throws Exception{return lstat(p);}
                public int read(byte[] b)throws Exception{return input.read(ByteBuffer.wrap(b));}
                public void close()throws Exception{input.close();}
            };
        }
    }
    static void run() {
        try {
            Path base=Paths.get(System.getProperty("ownruns.fixtureDir")).toAbsolutePath().normalize();
            OwnRunsProbeHostTest.check(base.startsWith(Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()),"real fixtures stay in system Temp");
            Path good=base.resolve("good/runs"),dir=good.resolve("run-1-aaaaaaaa");Files.createDirectories(dir);Files.write(dir.resolve(OwnRunsProbe.LEAF),new byte[]{97,98,99});
            Real host=new Real(good);OwnRunsProbe.Result actual=OwnRunsProbeHostTest.run(host);
            OwnRunsProbeHostTest.check(actual.successful()&&host.opens==1&&actual.leafHashes.get("run-1-aaaaaaaa").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),"real Windows fixture reads complete bytes without writes");
            Path leafRoot=base.resolve("leaf-link/runs"),leafDir=leafRoot.resolve("run-1-aaaaaaaa");Files.createDirectories(leafDir);
            Path leafTarget=base.resolve("leaf-target.txt");Files.write(leafTarget,new byte[]{1,2,3});Files.createSymbolicLink(leafDir.resolve(OwnRunsProbe.LEAF),leafTarget);
            host=new Real(leafRoot);actual=OwnRunsProbeHostTest.run(host);OwnRunsProbeHostTest.check(!actual.successful()&&host.opens==0,"real leaf symlink rejected before content open");
            Path dirRoot=base.resolve("directory-link/runs"),dirTarget=base.resolve("directory-target");Files.createDirectories(dirRoot);Files.createDirectories(dirTarget);Files.write(dirTarget.resolve(OwnRunsProbe.LEAF),new byte[]{4});Files.createSymbolicLink(dirRoot.resolve("run-1-aaaaaaaa"),dirTarget);
            host=new Real(dirRoot);actual=OwnRunsProbeHostTest.run(host);OwnRunsProbeHostTest.check(!actual.successful()&&host.opens==0,"real run-directory symlink rejected before child names/content");
            System.out.println("REAL_FS: Windows bytes success, actual leaf and directory symlink creation/rejection PASS; fixtures retained "+base);
            System.out.println("REAL_FS_LIMIT: synthetic Android prefixes/identity and POSIX attrs; host fstat model uses path attrs; no Android/ART/O_NOFOLLOW security claim");
        }catch(Exception failure){throw new AssertionError("Real fixture creation or verification failed; no skip-as-pass",failure);}
    }
}
