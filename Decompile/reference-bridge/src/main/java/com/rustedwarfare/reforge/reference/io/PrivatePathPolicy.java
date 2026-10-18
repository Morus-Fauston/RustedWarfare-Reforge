package com.rustedwarfare.reforge.reference.io;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Pure path checks for a controlled reference runner; not a concurrent security sandbox. */
public final class PrivatePathPolicy {
    public enum Access { READ, LIST, EXISTS, WRITE }
    public static final class PathViolation extends IOException {
        private final String code;
        public PathViolation(String code, String message) { super(message); this.code = code; }
        public PathViolation(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
        public String code() { return code; }
    }
    public String checkAsset(String path, Access access) throws PathViolation {
        if (access == Access.WRITE) throw new PathViolation("asset-write-denied", "Assets are read-only");
        if (path == null || path.trim().isEmpty() || access == null || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0 || path.startsWith("/"))
            throw new PathViolation("invalid-path", "Invalid asset name");
        String name = path.startsWith("assets/") ? path.substring(7) : path;
        for (String part : name.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new PathViolation("invalid-path", "Ambiguous asset component");
        return name;
    }
    private final Path root;
    public PrivatePathPolicy(Path root) throws PathViolation {
        if (root == null || !root.isAbsolute())
            throw new PathViolation("invalid-root", "An existing absolute real directory is required");
        try {
            BasicFileAttributes attributes = attributesOrMissing(root);
            if (attributes == null || attributes.isSymbolicLink() || !attributes.isDirectory())
                throw new PathViolation("invalid-root", "An existing absolute real directory is required");
            this.root = root.toRealPath();
        } catch (PathViolation ex) { throw ex; }
          catch (IOException ex) { throw new PathViolation("io-error", "Cannot resolve private root", ex); }
    }
    public Path checkFile(String path, Access access) throws PathViolation {
        if (path == null || path.trim().isEmpty() || access == null || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0)
            throw new PathViolation("invalid-path", "Invalid private path");
        boolean drive = path.matches("^[A-Za-z]:/.*") && FileSystems.getDefault().getSeparator().equals("\\");
        if (path.indexOf(':') >= 0 && !(drive && path.indexOf(':', 2) < 0))
            throw new PathViolation("invalid-path", "URI or ambiguous drive path is forbidden");
        String parts = drive ? path.substring(3) : path.startsWith("/") ? path.substring(1) : path;
        if (parts.endsWith("/")) parts = parts.substring(0, parts.length() - 1);
        for (String part : parts.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new PathViolation("invalid-path", "Ambiguous path component");
        final Path candidate;
        try { candidate = root.resolve(path).normalize(); }
        catch (InvalidPathException ex) { throw new PathViolation("invalid-path", "Platform rejected the path", ex); }
        try {
            BasicFileAttributes rootAttributes = attributesOrMissing(root);
            if (rootAttributes == null || !rootAttributes.isDirectory() || rootAttributes.isSymbolicLink() || !root.toRealPath().equals(root))
                throw new PathViolation("invalid-root", "Private root changed or disappeared");
            if (!candidate.startsWith(root)) throw new PathViolation("outside-root", "Path is outside the private root");
            Path cursor = root;
            for (Path part : root.relativize(candidate)) {
                cursor = cursor.resolve(part);
                if (Files.isSymbolicLink(cursor)) throw new PathViolation("symbolic-link", "Links are forbidden in private paths");
                if (attributesOrMissing(cursor) != null && !cursor.toRealPath().startsWith(root))
                    throw new PathViolation("outside-root", "Resolved path escaped the private root");
            }
            boolean exists = attributesOrMissing(candidate) != null;
            if (!exists && (access == Access.READ || access == Access.LIST))
                throw new PathViolation("missing-path", "Required private path is missing");
            if (!exists) {
                BasicFileAttributes parentAttributes = attributesOrMissing(candidate.getParent());
                if (parentAttributes == null) throw new PathViolation("missing-path", "Private parent is missing");
                if (!parentAttributes.isDirectory()) throw new PathViolation("wrong-type", "Private parent is not a directory");
            }
            BasicFileAttributes targetAttributes = attributesOrMissing(candidate);
            if (exists && access == Access.LIST && !targetAttributes.isDirectory())
                throw new PathViolation("wrong-type", "Listing requires a directory");
            if (exists && (access == Access.READ || access == Access.WRITE) && !targetAttributes.isRegularFile())
                throw new PathViolation("wrong-type", "File access requires a regular file");
        } catch (PathViolation ex) { throw ex; }
          catch (IOException ex) { throw new PathViolation("io-error", "Cannot inspect private path", ex); }
        return candidate;
    }
    private static BasicFileAttributes attributesOrMissing(Path path) throws IOException {
        try { return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException ex) { return null; }
    }
}
