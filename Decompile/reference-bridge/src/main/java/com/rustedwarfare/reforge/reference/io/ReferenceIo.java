package com.rustedwarfare.reforge.reference.io;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;

/** Trusted explicit route checks only. Returned values must be used by the real caller's sink. */
public final class ReferenceIo {
    private final PrivatePathPolicy policy;
    private final FatalLatch latch;
    private final Path bridge;
    public ReferenceIo(Path root,Path bridgeDirectory,FatalLatch latch) {
        if(latch==null)throw new IllegalArgumentException("A fatal latch is required");
        this.latch=latch;latch.check();
        try {
            policy=new PrivatePathPolicy(root);
            if(bridgeDirectory==null || !bridgeDirectory.isAbsolute())throw latch.trip("invalid-bridge-root","Bridge directory must be absolute and non-null",null);
            Path checked=policy.checkFile(bridgeDirectory.toString().replace('\\','/'),PrivatePathPolicy.Access.LIST);
            Path canonicalRoot=policy.checkFile(root.toString().replace('\\','/'),PrivatePathPolicy.Access.LIST);
            if(checked.equals(canonicalRoot) || !checked.startsWith(canonicalRoot) || !checked.getFileName().toString().equals("bridge"))
                throw latch.trip("invalid-bridge-root","Expected a private bridge directory",null);
            bridge=checked;
        } catch(PrivatePathPolicy.PathViolation error) {throw latch.trip(error.code(),error.getMessage(),error);}
    }
    public Path privateFile(String path,PrivatePathPolicy.Access access) {
        latch.check();
        if(access==null || path==null)throw latch.trip("invalid-path","Path and access are required",null);
        rejectMarker(path);
        try {
            if(!Paths.get(path).isAbsolute())throw latch.trip("relative-private-path","Private paths must be absolute",null);
            return policy.checkFile(path,access);
        } catch(InvalidPathException error) {throw latch.trip("invalid-path","Platform rejected private path",error);}

        catch(PrivatePathPolicy.PathViolation error){throw latch.trip(error.code(),error.getMessage(),error);}
    }
    public String asset(String name,PrivatePathPolicy.Access access) {
        latch.check();
        if(access==null)throw latch.trip("invalid-path","Asset access is required",null);
        if(access==PrivatePathPolicy.Access.WRITE)throw latch.trip("asset-write-denied","Assets are read-only",null);
        if(name!=null)rejectMarker(name);
        try {return policy.checkAsset(name,access);}
        catch(PrivatePathPolicy.PathViolation error){throw latch.trip(error.code(),error.getMessage(),error);}
    }
    public static final class RenamePermit {
        private final Path source,destination;
        private RenamePermit(Path source,Path destination){this.source=source;this.destination=destination;}
        public Path source(){return source;}
        public Path destination(){return destination;}
    }
    public RenamePermit prepareRename(String source,String destination) {
        latch.check();
        Path from=privateFile(source,PrivatePathPolicy.Access.READ);
        Path to=privateFile(destination,PrivatePathPolicy.Access.WRITE);
        if(!from.startsWith(bridge) || from.equals(bridge) || !to.startsWith(bridge) || to.equals(bridge) ||
            !from.getParent().equals(to.getParent()) || from.equals(to)) throw latch.trip("rename-scope","Rename must remain within one private bridge directory",null);
        try {
            Files.readAttributes(to,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        } catch(NoSuchFileException missing) {return new RenamePermit(from,to);}
          catch(IOException error) {throw latch.trip("io-error","Cannot inspect rename destination",error);}
        throw latch.trip("destination-exists","Rename destination already exists",null);
    }
    /** Validated metadata path only; does not open, list or create its target. */
    public Path privateMetadataPath(String path) {
        latch.check();
        try {
            Path checked = privateFile(path, PrivatePathPolicy.Access.EXISTS);
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        checked, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink())
                    throw latch.trip("symbolic-link", "Links are forbidden in private metadata", null);
                if (!attributes.isRegularFile() && !attributes.isDirectory())
                    throw latch.trip("wrong-type", "Metadata requires a regular file or directory", null);
                return privateFile(checked.toString().replace('\\', '/'), PrivatePathPolicy.Access.EXISTS);
            } catch (NoSuchFileException missing) {
                checked = privateFile(path, PrivatePathPolicy.Access.EXISTS);
                existingDirectory(checked.getParent().toString().replace('\\', '/'));
                return checked;
            }
        } catch (IOException | SecurityException error) {
            throw latch.trip("io-error", "Cannot inspect private metadata path", error);
        }
    }
    public long lastModified(String path) {
        latch.check();
        try {
            Path checked = privateFile(path, PrivatePathPolicy.Access.EXISTS);
            BasicFileAttributes attributes = Files.readAttributes(
                    checked, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink())
                throw latch.trip("symbolic-link", "Links are forbidden in private metadata", null);
            if (!attributes.isRegularFile() && !attributes.isDirectory())
                throw latch.trip("wrong-type", "Metadata requires a regular file or directory", null);
            privateFile(checked.toString().replace('\\', '/'), PrivatePathPolicy.Access.EXISTS);
            return attributes.lastModifiedTime().toMillis();
        } catch (NoSuchFileException missing) {
            try {
                Path checked = privateFile(path, PrivatePathPolicy.Access.EXISTS);
                existingDirectory(checked.getParent().toString().replace('\\', '/'));
                return 0L;
            } catch (SecurityException error) {
                throw latch.trip("io-error", "Cannot inspect private modification time", error);
            }
        } catch (IOException | SecurityException error) {
            throw latch.trip("io-error", "Cannot inspect private modification time", error);
        }
    }
    public Path existingDirectory(String path){return privateFile(path,PrivatePathPolicy.Access.LIST);}
    public void denyDelete(String path){latch.check();throw latch.trip("unsupported-io-operation","Delete is not allowed",null);}
    private void rejectMarker(String path) {
        if(path.contains("content:") || path.contains(".[saflink]") || path.contains("/saf-virtual/") || path.contains(".rwmod/") || path.contains(".rwmod\\"))
            throw latch.trip("unsupported-route","SAF and virtual archive routes are forbidden",null);
    }
}
