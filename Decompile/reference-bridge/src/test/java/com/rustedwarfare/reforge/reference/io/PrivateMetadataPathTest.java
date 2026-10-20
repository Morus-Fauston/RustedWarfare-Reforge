package com.rustedwarfare.reforge.reference.io;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

/** Owned real filesystem fixtures; no Android or original-game dependencies. */
public final class PrivateMetadataPathTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static String raw(Path path) { return path.toString().replace('\\', '/'); }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static FatalLatch.Failure failure(String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (FatalLatch.Failure error) {
            check(code.equals(error.code()), "Expected " + code + "; got " + error.code());
            return error;
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Paths.get(args[0]), "private-metadata-").toRealPath();
        Path bridge = Files.createDirectory(root.resolve("bridge"));
        Path directory = Files.createDirectory(root.resolve("maps"));
        Path file = Files.write(directory.resolve("owned.bin"), new byte[] {3, 1, 4});
        Path missing = directory.resolve("absent.bin");
        ReferenceIo valid = new ReferenceIo(root, bridge, new FatalLatch());
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
        check(valid.privateMetadataPath(raw(root)).equals(root), "Root metadata path");
        check(valid.privateMetadataPath(raw(directory)).equals(directory), "Directory metadata path");
        check(valid.privateMetadataPath(raw(file)).equals(file), "File metadata path");
        check(valid.privateMetadataPath(raw(missing)).equals(missing), "Legal absent leaf metadata path");
        check(!Files.exists(missing), "Metadata must not create its target");
        check(Arrays.equals(Files.readAllBytes(file), new byte[] {3, 1, 4}), "Contents preserved");
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
        check(before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime()),
                "Metadata must not modify file size or timestamp");
        failure("wrong-type", () -> new ReferenceIo(root, bridge, new FatalLatch())
                .privateFile(raw(directory), PrivatePathPolicy.Access.READ));
        failure("missing-path", () -> new ReferenceIo(root, bridge, new FatalLatch())
                .privateMetadataPath(raw(root.resolve("missing/leaf"))));
        failure("outside-root", () -> new ReferenceIo(root, bridge, new FatalLatch())
                .privateMetadataPath(raw(root.getParent())));
        failure("relative-private-path", () -> new ReferenceIo(root, bridge, new FatalLatch())
                .privateMetadataPath("maps/owned.bin"));
        failure("invalid-path", () -> new ReferenceIo(root, bridge, new FatalLatch())
                .privateMetadataPath(raw(root) + "/maps/../owned.bin"));
        failure("unsupported-route", () -> new ReferenceIo(root, bridge, new FatalLatch())
                .privateMetadataPath("content://external/file"));
        FatalLatch latch = new FatalLatch();
        ReferenceIo blocked = new ReferenceIo(root, bridge, latch);
        FatalLatch.Failure first = failure("invalid-path", () -> blocked.privateMetadataPath(null));
        check(first == latch.firstFailure(), "Latch retains the exact first failure");
        check(first == failure("invalid-path", () -> blocked.privateMetadataPath(raw(directory))),
                "Valid metadata cannot clear first failure");
        check(first == failure("invalid-path", () -> blocked.existingDirectory(raw(root))),
                "Other operations retain first failure");
        System.out.println("PASS PrivateMetadataPath checks=" + checks + " fixture=" + root);
        System.out.println("UNVERIFIED Android, original caller, permissions, special files, links and concurrent replacement");
    }
}
