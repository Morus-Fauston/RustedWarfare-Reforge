package com.rustedwarfare.reforge.reference.io;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Standalone explicit assertions, not JVM -ea dependent. Fixtures are retained, never deleted. */
public final class PrivatePathPolicyTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + " actual=" + actual);
    }
    private interface Checked { void run() throws Exception; }
    private static void denied(String code, Checked operation) throws Exception {
        checks++;
        try { operation.run(); }
        catch (PrivatePathPolicy.PathViolation ex) { equal(code, ex.code()); return; }
        throw new AssertionError("Expected rejection: " + code);
    }
    public static void main(String[] args) throws Exception {
        Path parent = Paths.get(args[0]).toAbsolutePath();
        Path root = Files.createDirectory(parent.resolve("fixture-" + java.util.UUID.randomUUID()));
        Path maps = Files.createDirectory(root.resolve("maps"));
        Path input = Files.write(maps.resolve("a.tmx"), "fixture".getBytes(StandardCharsets.UTF_8));
        PrivatePathPolicy policy = new PrivatePathPolicy(root);
        equal(input.toRealPath(), policy.checkFile("maps/a.tmx", PrivatePathPolicy.Access.READ));
        equal("fixture", new String(Files.readAllBytes(input), StandardCharsets.UTF_8));
        denied("invalid-path", () -> policy.checkFile("../outside", PrivatePathPolicy.Access.READ));
        denied("invalid-path", () -> policy.checkFile("content://external/file", PrivatePathPolicy.Access.READ));
        Path sibling = Files.createDirectory(parent.resolve(root.getFileName() + "-sibling"));
        denied("outside-root", () -> policy.checkFile(sibling.toString().replace('\\', '/'), PrivatePathPolicy.Access.LIST));
        equal(input.toRealPath(), policy.checkFile(input.toString().replace('\\', '/'), PrivatePathPolicy.Access.READ));
        denied("invalid-root", () -> new PrivatePathPolicy(root.resolve("absent")));
        denied("invalid-root", () -> new PrivatePathPolicy(input));
        denied("missing-path", () -> policy.checkFile("maps/missing.tmx", PrivatePathPolicy.Access.READ));
        denied("wrong-type", () -> policy.checkFile("maps/a.tmx", PrivatePathPolicy.Access.LIST));
        denied("wrong-type", () -> policy.checkFile("maps", PrivatePathPolicy.Access.READ));
        denied("wrong-type", () -> policy.checkFile("maps", PrivatePathPolicy.Access.WRITE));
        denied("missing-path", () -> policy.checkFile("absent/new.txt", PrivatePathPolicy.Access.WRITE));
        Path fresh = policy.checkFile("maps/new.txt", PrivatePathPolicy.Access.WRITE);
        equal(maps.resolve("new.txt"), fresh);
        equal(false, Files.exists(fresh));
        equal(fresh, policy.checkFile("maps/new.txt", PrivatePathPolicy.Access.EXISTS));
        equal(root.toRealPath(), policy.checkFile(root.toString().replace('\\', '/'), PrivatePathPolicy.Access.LIST));
        denied("wrong-type", () -> policy.checkFile(root.toString().replace('\\', '/'), PrivatePathPolicy.Access.WRITE));
        equal("units/tank.ini", policy.checkAsset("assets/units/tank.ini", PrivatePathPolicy.Access.READ));
        equal("maps/demo.tmx", policy.checkAsset("maps/demo.tmx", PrivatePathPolicy.Access.LIST));
        denied("asset-write-denied", () -> policy.checkAsset("units/tank.ini", PrivatePathPolicy.Access.WRITE));
        denied("invalid-path", () -> policy.checkAsset("assets/../secret", PrivatePathPolicy.Access.READ));
        denied("invalid-path", () -> policy.checkAsset("/sdcard/a", PrivatePathPolicy.Access.READ));
        denied("invalid-path", () -> policy.checkAsset("content://private", PrivatePathPolicy.Access.READ));
        for (String bad : new String[] {"", " ", "./a", "maps//a.tmx", "maps/../a.tmx", "a\\b", "C:foo", "\\\\server\\share", "maps/\0bad"})
            denied("invalid-path", () -> policy.checkFile(bad, PrivatePathPolicy.Access.EXISTS));
        denied("invalid-path", () -> policy.checkFile(null, PrivatePathPolicy.Access.READ));
        denied("invalid-path", () -> policy.checkFile("maps/a.tmx", null));
        equal(maps.resolve("name..ini"), policy.checkFile("maps/name..ini", PrivatePathPolicy.Access.EXISTS));
        equal("units/Case..ini", policy.checkAsset("units/Case..ini", PrivatePathPolicy.Access.READ));
        Path outside = Files.write(sibling.resolve("secret"), new byte[] {1});
        denied("outside-root", () -> policy.checkFile(outside.toString().replace('\\', '/'), PrivatePathPolicy.Access.READ));
        Path link = root.resolve("link");
        boolean linksSupported = false;
        try { Files.createSymbolicLink(link, outside); linksSupported = true; }
        catch (java.io.IOException | UnsupportedOperationException | SecurityException ex) {
            System.out.println("UNVERIFIED symlink creation: " + ex.getClass().getName() + ": " + ex.getMessage());
        }
        if (linksSupported) {
            denied("symbolic-link", () -> policy.checkFile("link", PrivatePathPolicy.Access.READ));
            Path directoryLink = Files.createSymbolicLink(root.resolve("linkdir"), sibling);
            denied("symbolic-link", () -> policy.checkFile("linkdir/new", PrivatePathPolicy.Access.WRITE));
        }
        // Replace only this test's own directory by renaming it; never delete any fixture.
        Path originalRoot = root;
        Path retained = parent.resolve(root.getFileName() + "-retained");
        if (!root.isAbsolute() || !root.getParent().equals(parent) || !root.getFileName().toString().startsWith("fixture-") || Files.exists(retained))
            throw new AssertionError("Fixture move target is not the exact newly-created test directory: " + root);
        System.out.println("OWNED_FIXTURE_MOVE source=" + root + " target=" + retained);
        Files.move(root, retained);
        denied("invalid-root", () -> policy.checkFile("maps/a.tmx", PrivatePathPolicy.Access.READ));
        if (linksSupported) {
            Files.createSymbolicLink(originalRoot, retained);
            denied("invalid-root", () -> policy.checkFile("maps/a.tmx", PrivatePathPolicy.Access.READ));
        }
        System.out.println("PASS checks=" + checks + " symlinkTested=" + linksSupported + " retained=" + retained);
    }
}
