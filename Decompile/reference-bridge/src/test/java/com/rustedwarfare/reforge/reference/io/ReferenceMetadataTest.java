package com.rustedwarfare.reforge.reference.io;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/** Real owned temporary filesystem fixtures. No Context or static-root substitutes. */
public final class ReferenceMetadataTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean accepted, String message) {
        checks++;
        if (!accepted) throw new AssertionError(message);
    }
    private static String raw(Path path) { return path.toString().replace('\\', '/'); }
    private static FatalLatch.Failure failure(String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (FatalLatch.Failure error) {
            check(code.equals(error.code()), "expected=" + code + " actual=" + error.code());
            return error;
        }
    }
    private static ReferenceIo io(Path root, Path bridge) {
        return new ReferenceIo(root, bridge, new FatalLatch());
    }
    private static void timestamp(ReferenceIo io, Path target, long requested) throws Exception {
        Files.setLastModifiedTime(target, FileTime.fromMillis(requested));
        long actual = Files.readAttributes(target, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).lastModifiedTime().toMillis();
        long observed = io.lastModified(raw(target));
        check(actual == observed, "real filesystem timestamp must match: " + target);
        System.out.println("MTIME path=" + target + " requested=" + requested + " actual=" + actual + " observed=" + observed);
    }
    public static void main(String[] args) throws Exception {
        Path parent = Paths.get(args[0]).toAbsolutePath();
        Path root = Files.createTempDirectory(parent, "metadata-");
        Path bridge = Files.createDirectory(root.resolve("bridge"));
        Path maps = Files.createDirectory(root.resolve("maps"));
        Path file = Files.write(maps.resolve("owned.txt"), new byte[] {1, 2, 3});
        ReferenceIo valid = io(root, bridge);
        timestamp(valid, file, 1600000000123L);
        timestamp(valid, maps, 1600000000456L);
        timestamp(valid, root, 1600000000789L);
        timestamp(valid, file, 0L);
        Path absent = maps.resolve("absent.txt");
        check(valid.lastModified(raw(absent)) == 0L, "legal missing leaf returns zero");
        check(!Files.exists(absent), "metadata must not create the missing leaf");
        check(Files.readAllBytes(file).length == 3, "metadata preserves file contents");
        failure("missing-path", () -> io(root, bridge).lastModified(raw(root.resolve("missing/leaf"))));
        FatalLatch.Failure parentType;
        try { io(root, bridge).lastModified(raw(file.resolve("leaf"))); throw new AssertionError("Expected bad parent failure"); }
        catch (FatalLatch.Failure error) {
            parentType = error;
            check(error.code().equals("io-error") || error.code().equals("wrong-type"), "OS-dependent bad parent failure must be fatal");
        }
        System.out.println("PARENT_FILE_FATAL=" + parentType.code());
        failure("invalid-root", () -> io(root.resolve("missing-root"), bridge));
        Path sibling = Files.createTempDirectory(parent, root.getFileName() + "-sibling-");
        failure("outside-root", () -> io(root, bridge).lastModified(raw(sibling)));
        for (String path : new String[] {null, raw(root) + "/maps/../owned.txt",
                raw(root) + "//maps/owned.txt", raw(root) + "/maps/\0bad", raw(root) + "\\maps\\owned.txt"}) {
            failure("invalid-path", () -> io(root, bridge).lastModified(path));
        }
        for (String path : new String[] {"", "maps/owned.txt"})
            failure("relative-private-path", () -> io(root, bridge).lastModified(path));
        String whitespaceCode = FileSystems.getDefault().getSeparator().equals("\\")
                ? "invalid-path" : "relative-private-path";
        failure(whitespaceCode, () -> io(root, bridge).lastModified(" "));
        System.out.println("PLATFORM_WHITESPACE_FATAL=" + whitespaceCode);
        for (String marker : new String[] {"content://external/file", raw(root) + "/x.[saflink]/leaf",
                raw(root) + "/saf-virtual/leaf", raw(root) + "/x.rwmod/leaf"}) {
            failure("unsupported-route", () -> io(root, bridge).lastModified(marker));
        }
        FatalLatch latch = new FatalLatch();
        ReferenceIo blocked = new ReferenceIo(root, bridge, latch);
        FatalLatch.Failure first = failure("invalid-path", () -> blocked.lastModified(null));
        check(first == latch.firstFailure(), "latch owns exact first object");
        check(first == failure("invalid-path", () -> blocked.lastModified(raw(file))), "valid metadata rethrows first object");
        check(first == failure("invalid-path", () -> blocked.lastModified(null)), "invalid metadata rethrows first object");
        check(first == failure("invalid-path", () -> blocked.existingDirectory(raw(root))), "other API rethrows first object");
        int linkChecks = 0;
        try {
            Path leafLink = Files.createSymbolicLink(root.resolve("owned-leaf-link"), file);
            failure("symbolic-link", () -> io(root, bridge).lastModified(raw(leafLink)));
            linkChecks++;
            Path parentLink = Files.createSymbolicLink(root.resolve("owned-parent-link"), maps);
            failure("symbolic-link", () -> io(root, bridge).lastModified(raw(parentLink.resolve("owned.txt"))));
            linkChecks++;
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            System.out.println("UNVERIFIED symbolic-link remaining controls: " + error);
        }
        System.out.println("LINK_CHECKS=" + linkChecks + "/2");
        System.out.println("UNVERIFIED permission-denied/special-file/root-replacement/concurrent-race controls; no ACL, rename or delete mutation");
        System.out.println("PASS ReferenceMetadata checks=" + checks + " fixture=" + root);
    }
}
