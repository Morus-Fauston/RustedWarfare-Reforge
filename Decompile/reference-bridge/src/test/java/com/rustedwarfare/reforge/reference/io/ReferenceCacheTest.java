package com.rustedwarfare.reforge.reference.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Public cache lifecycle behaviors over owned real filesystem fixtures. */
public final class ReferenceCacheTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface Action { void run() throws Exception; }
    private static FatalLatch.Failure rejected(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected fatal cache rejection"); }
        catch (FatalLatch.Failure failure) { return failure; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("fixture parent and scenario required");
        if ("all".equals(args[1])) {
            String[] scenarios = {"path", "miss", "write", "invalidate", "count", "clear", "negative", "lifecycle",
                    "copy-failure", "clear-preflight", "links", "concurrent", "partial-clear-failure", "wrong-type-write", "replace-failure",
                    "junction-type", "junction-root", "junction-ancestor", "junction-leaf"};
            for (String scenario : scenarios) main(new String[] {args[0], scenario});
            System.out.println("PASS ReferenceCache all scenarios=" + scenarios.length);
            return;
        }
        Path root = Files.createTempDirectory(Paths.get(args[0]), "cache-").toRealPath();
        Path parent = "junction-ancestor".equals(args[1]) ? Files.createDirectory(root.resolve("envelope")) : root;
        Path cacheRoot = Files.createDirectory(parent.resolve("cache"));
        for (String type : new String[] {"mods-info", "mods-dir-search", "mods-dir-music"})
            Files.createDirectory(cacheRoot.resolve(type + ".cachedata"));
        FatalLatch latch = new FatalLatch();
        ReferenceCache cache = new ReferenceCache(cacheRoot, latch);
        Path expected = cacheRoot.resolve("mods-info.cachedata/owned.data");
        if ("junction-root".equals(args[1]) || "junction-ancestor".equals(args[1]) || "junction-leaf".equals(args[1])) {
            if (java.io.File.separatorChar != '\\') throw new IllegalStateException("Windows junction fixture required");
            boolean leaf = "junction-leaf".equals(args[1]);
            Path alias = leaf ? expected : "junction-root".equals(args[1]) ? cacheRoot : parent;
            Path destination = leaf ? cacheRoot.resolve("mods-dir-music.cachedata") : root.resolve("relocated");
            if (!leaf) Files.move(alias, destination);
            Path sentinel = Files.write(destination.resolve("sentinel.data"), new byte[] {9});
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    alias.toString(), destination.toString()).inheritIO().start();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Junction creation timed out");
            }
            check(process.exitValue() == 0, "junction fixture creation must succeed");
            System.out.println("JUNCTION alias=" + alias + " symbolic=" + Files.isSymbolicLink(alias)
                    + " real=" + alias.toRealPath());
            if (!leaf) rejected(() -> new ReferenceCache(cacheRoot, new FatalLatch()));
            FatalLatch.Failure first = rejected(() -> cache.path("mods-info", "owned.data"));
            check(first == rejected(cache::clear), "alias rejection must preserve the first failure object");
            check(java.util.Arrays.equals(new byte[] {9}, Files.readAllBytes(sentinel)), "alias target bytes must be preserved");
            System.out.println("PASS ReferenceCache " + args[1] + " checks=3 code=" + first.code() + " fixture=" + root);
            return;
        }
        if ("junction-type".equals(args[1])) {
            if (java.io.File.separatorChar != '\\') throw new IllegalStateException("Windows junction fixture required");
            Path type = cacheRoot.resolve("mods-info.cachedata");
            Path destination = cacheRoot.resolve("mods-dir-music.cachedata");
            Path sentinel = Files.write(destination.resolve("sentinel.data"), new byte[] {9});
            Files.delete(type);
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    type.toString(), destination.toString()).inheritIO().start();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Junction creation timed out");
            }
            check(process.exitValue() == 0, "junction fixture creation must succeed");
            System.out.println("JUNCTION type=" + type + " symbolic=" + Files.isSymbolicLink(type)
                    + " real=" + type.toRealPath());
            FatalLatch.Failure constructorFailure = rejected(() -> new ReferenceCache(cacheRoot, new FatalLatch()));
            check("symbolic-link".equals(constructorFailure.code()), "constructor must reject same-root cross-type junction");
            FatalLatch.Failure first = rejected(() -> cache.path("mods-info", "owned.data"));
            check("symbolic-link".equals(first.code()), "published helper must reject replaced type junction");
            check(first == rejected(cache::clear), "junction rejection must share the first fatal object");
            check(java.util.Arrays.equals(new byte[] {9}, Files.readAllBytes(sentinel)), "rejection must preserve target bytes");
            System.out.println("PASS ReferenceCache junction-type checks=5 fixture=" + root);
            return;
        }
        if ("replace-failure".equals(args[1])) {
            if (java.io.File.separatorChar != '\\') throw new IllegalStateException("Windows DOS read-only failure fixture required");
            Files.write(expected, new byte[] {5});
            Files.setAttribute(expected, "dos:readonly", true);
            FatalLatch.Failure first = rejected(() -> cache.write("mods-info", "owned.data", new ByteArrayInputStream(new byte[] {6})));
            check("io-error".equals(first.code()), "real replacement failure must latch io-error");
            check(first.getCause() instanceof java.nio.file.AccessDeniedException, "replace fixture must produce actual OS access denial");
            check(java.util.Arrays.equals(new byte[] {5}, Files.readAllBytes(expected)), "record observed old final after this OS failure");
            check(java.util.Arrays.equals(new byte[] {6}, Files.readAllBytes(expected.resolveSibling("owned.data.tmp"))),
                    "failed replacement must preserve its actual temporary bytes");
            check(first == rejected(cache::clear), "failed replacement cannot resume cleanup");
            System.out.println("PASS ReferenceCache replace-failure checks=5 cause=" + first.getCause() + " fixture=" + root);
            return;
        }
        if ("partial-clear-failure".equals(args[1])) {
            if (java.io.File.separatorChar != '\\') throw new IllegalStateException("Windows DOS read-only failure fixture required");
            Files.write(expected, new byte[] {5});
            Path later = Files.write(cacheRoot.resolve("mods-dir-music.cachedata/locked.data"), new byte[] {6});
            Files.setAttribute(later, "dos:readonly", true);
            FatalLatch.Failure first = rejected(cache::clear);
            check("io-error".equals(first.code()), "real deletion failure must latch io-error");
            check(first.getCause() instanceof java.nio.file.AccessDeniedException, "fixture must produce a real OS access-denied cause");
            check(!Files.exists(expected), "completed earlier deletion must remain recorded");
            check(java.util.Arrays.equals(new byte[] {6}, Files.readAllBytes(later)), "failed later leaf must remain as evidence");
            check(first == rejected(cache::count), "partial clear cannot continue as a successful session");
            System.out.println("PASS ReferenceCache partial-clear-failure checks=5 cause=" + first.getCause() + " fixture=" + root);
            return;
        }
        if ("wrong-type-write".equals(args[1])) {
            Files.createDirectory(expected.resolveSibling("owned.data.tmp"));
            final int[] reads = {0};
            InputStream input = new InputStream() {
                @Override public int read() { reads[0]++; return -1; }
            };
            FatalLatch.Failure first = rejected(() -> cache.write("mods-info", "owned.data", input));
            check("wrong-type".equals(first.code()), "tmp directory must be rejected");
            check(reads[0] == 0, "all leaf validation must precede consuming borrowed input");
            check(!Files.exists(expected), "invalid tmp must not create the final");
            System.out.println("PASS ReferenceCache wrong-type-write checks=3 fixture=" + root);
            return;
        }
        if ("links".equals(args[1])) {
            Path outside = Files.write(root.resolve("outside.txt"), new byte[] {9});
            Path link = Files.createSymbolicLink(expected, outside);
            FatalLatch.Failure first = rejected(() -> cache.open("mods-info", "owned.data"));
            check("symbolic-link".equals(first.code()), "linked leaf must be rejected");
            ReferenceCache cleaner = new ReferenceCache(cacheRoot, new FatalLatch());
            check("symbolic-link".equals(rejected(cleaner::clear).code()), "cleanup must reject a linked leaf");
            check(Files.isSymbolicLink(link), "rejected cleanup must preserve the link evidence");
            check(java.util.Arrays.equals(new byte[] {9}, Files.readAllBytes(outside)), "link target must remain untouched");
            Path type = cacheRoot.resolve("mods-dir-search.cachedata");
            Files.delete(type);
            Files.createSymbolicLink(type, root);
            check("symbolic-link".equals(rejected(() -> new ReferenceCache(cacheRoot, new FatalLatch())).code()),
                    "linked fixed type must be rejected before publication");
            System.out.println("PASS ReferenceCache links checks=5 fixture=" + root);
            return;
        }
        if ("concurrent".equals(args[1])) {
            java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
            Thread[] workers = new Thread[2];
            for (int index = 0; index < workers.length; index++) {
                final byte value = (byte) (index + 3);
                workers[index] = new Thread(() -> {
                    try {
                        for (int iteration = 0; iteration < 20; iteration++)
                            cache.write("mods-info", "owned.data", new ByteArrayInputStream(new byte[] {value, value, value}));
                    } catch (Throwable problem) { error.compareAndSet(null, problem); }
                });
                workers[index].start();
            }
            for (Thread worker : workers) worker.join(10000L);
            for (Thread worker : workers) check(!worker.isAlive(), "bounded writers must complete");
            if (error.get() != null) throw new AssertionError("Serialized writes failed", error.get());
            byte[] bytes = Files.readAllBytes(expected);
            check(bytes.length == 3 && bytes[0] == bytes[1] && bytes[1] == bytes[2], "same-helper writes must not mix bytes");
            check(cache.count() == 1, "serialized writes leave one final leaf");
            check(!Files.exists(expected.resolveSibling("owned.data.tmp")), "serialized writes leave no tmp");
            System.out.println("PASS ReferenceCache concurrent checks=5 fixture=" + root);
            return;
        }
        if ("lifecycle".equals(args[1])) {
            for (String type : new String[] {"mods-info", "mods-dir-search", "mods-dir-music"}) {
                Path file = cacheRoot.resolve(type + ".cachedata/percent%%47.data");
                Files.write(file, new byte[] {9});
                cache.write(type, "percent%%47.data", new ByteArrayInputStream(new byte[] {0, -1, 2}));
                Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1000L));
                try (java.io.FileInputStream stream = cache.open(type, "percent%%47.data")) {
                    check(stream.read() == 0 && stream.read() == 255 && stream.read() == 2 && stream.read() == -1,
                            "hit must return the newly replaced exact bytes");
                }
                check(Files.getLastModifiedTime(file).toMillis() > 1000L, "hit must touch the old timestamp");
            }
            check(cache.count() == 3, "all three types retain their own final leaf");
            check(cache.clear() == 3, "lifecycle cleanup removes only the generated leaves");
            System.out.println("PASS ReferenceCache lifecycle checks=8 fixture=" + root);
            return;
        }
        if ("copy-failure".equals(args[1])) {
            Files.write(expected, new byte[] {5});
            final IOException original = new IOException("owned-input-failure");
            final boolean[] closed = {false};
            InputStream input = new InputStream() {
                @Override public int read() throws IOException { throw original; }
                @Override public void close() { closed[0] = true; }
            };
            FatalLatch.Failure first = rejected(() -> cache.write("mods-info", "owned.data", input));
            check(first.getCause() == original, "copy failure must preserve the original cause");
            check(java.util.Arrays.equals(new byte[] {5}, Files.readAllBytes(expected)), "copy failure before replace preserves the old final");
            check(!closed[0], "failed writer must not close the borrowed input");
            check(Files.exists(expected.resolveSibling("owned.data.tmp")), "failed write evidence must remain");
            check(first == rejected(cache::clear), "cleanup cannot continue after copy failure");
            check(first == rejected(() -> cache.open("mods-info", "owned.data")), "read cannot continue after copy failure");
            System.out.println("PASS ReferenceCache copy-failure checks=6 fixture=" + root);
            return;
        }
        if ("clear-preflight".equals(args[1])) {
            Files.write(expected, new byte[] {5});
            Path nested = Files.createDirectory(cacheRoot.resolve("mods-dir-music.cachedata/nested"));
            Path preserved = Files.write(nested.resolve("untouched"), new byte[] {6});
            FatalLatch.Failure first = rejected(cache::clear);
            check("wrong-type".equals(first.code()), "nested directory is rejected as a non-leaf");
            check(java.util.Arrays.equals(new byte[] {5}, Files.readAllBytes(expected)), "all-type preflight must precede the first deletion");
            check(java.util.Arrays.equals(new byte[] {6}, Files.readAllBytes(preserved)), "clear must not recurse");
            System.out.println("PASS ReferenceCache clear-preflight checks=3 fixture=" + root);
            return;
        }
        if ("negative".equals(args[1])) {
            int cases = 0;
            for (String type : new String[] {null, "", "MODS-INFO", "mods-info.cachedata", "../mods-info", "bridge"}) {
                ReferenceCache selected = new ReferenceCache(cacheRoot, new FatalLatch());
                FatalLatch.Failure first = rejected(() -> selected.path(type, "owned.data"));
                check(first == rejected(() -> selected.path("mods-info", "valid.data")), "invalid type must retain first fatal");
                cases++;
            }
            for (String key : new String[] {null, "", ".", "..", "../x", "x/y", "x\\y", "content:x", "x\0y"}) {
                ReferenceCache selected = new ReferenceCache(cacheRoot, new FatalLatch());
                rejected(() -> selected.path("mods-info", key));
                cases++;
            }
            if (java.io.File.separatorChar == '\\') {
                ReferenceCache selected = new ReferenceCache(cacheRoot, new FatalLatch());
                rejected(() -> selected.path("mods-info", "<"));
                cases++;
            }
            System.out.println("PASS ReferenceCache negative checks=" + cases + " fixture=" + root);
            return;
        }
        if ("clear".equals(args[1])) {
            Files.write(expected, new byte[] {3});
            Files.write(cacheRoot.resolve("mods-dir-search.cachedata/a.meta"), new byte[] {4});
            Files.write(cacheRoot.resolve("mods-dir-music.cachedata/b.data.tmp"), new byte[] {5});
            Path unrelated = Files.write(cacheRoot.resolve("unrelated.tmp"), new byte[] {6});
            check(cache.clear() == 3, "clear reports the actual deleted direct leaves");
            check(cache.count() == 0, "clear must leave all fixed types empty");
            check(java.util.Arrays.equals(new byte[] {6}, Files.readAllBytes(unrelated)), "clear must preserve cache-root temp files");
            check(Files.isDirectory(cacheRoot.resolve("mods-info.cachedata")), "clear must preserve fixed directories");
            check(cache.clear() == 0, "empty cleanup is successful");
            System.out.println("PASS ReferenceCache clear checks=5 fixture=" + root);
            return;
        }
        if ("count".equals(args[1])) {
            check(cache.count() == 0, "empty fixed types must count zero");
            Files.write(expected, new byte[] {3});
            Files.write(cacheRoot.resolve("mods-dir-search.cachedata/a.meta"), new byte[] {4});
            Files.write(cacheRoot.resolve("mods-dir-music.cachedata/b.data.tmp"), new byte[] {5});
            Files.write(cacheRoot.resolve("unrelated.tmp"), new byte[] {6});
            check(cache.count() == 3, "count only the three fixed types including tmp leaves");
            check(Files.exists(expected), "count must preserve leaves");
            System.out.println("PASS ReferenceCache count checks=3 fixture=" + root);
            return;
        }
        if ("invalidate".equals(args[1])) {
            Files.write(expected, new byte[] {3});
            Path sibling = Files.write(expected.resolveSibling("owned.data.tmp"), new byte[] {7});
            cache.invalidate("mods-info", "owned.data");
            check(!Files.exists(expected), "invalidate must delete the exact leaf");
            check(java.util.Arrays.equals(new byte[] {7}, Files.readAllBytes(sibling)), "invalidate must preserve siblings");
            cache.invalidate("mods-info", "owned.data");
            check(!latch.failed(), "missing invalidation is allowed");
            System.out.println("PASS ReferenceCache invalidate checks=3 fixture=" + root);
            return;
        }
        if ("write".equals(args[1])) {
            final boolean[] inputClosed = {false};
            ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {0, 1, 127, -1}) {
                @Override public void close() { inputClosed[0] = true; }
            };
            check(cache.write("mods-info", "owned.data", input), "successful write must return true");
            check(java.util.Arrays.equals(new byte[] {0, 1, 127, -1}, Files.readAllBytes(expected)), "final bytes must match input");
            check(!Files.exists(expected.resolveSibling("owned.data.tmp")), "successful write consumes only its tmp leaf");
            check(!inputClosed[0], "cache writer must not close the borrowed input");
            check(!latch.failed(), "successful write must not trip the latch");
            System.out.println("PASS ReferenceCache write checks=5 fixture=" + root);
            return;
        }
        if ("miss".equals(args[1])) {
            check(cache.open("mods-info", "owned.data") == null, "missing cache returns null");
            check(!Files.exists(expected), "cache miss must not create a leaf");
            check(!latch.failed(), "cache miss must not trip the latch");
            System.out.println("PASS ReferenceCache miss checks=3 fixture=" + root);
            return;
        }
        if (!"path".equals(args[1])) throw new IllegalArgumentException("Unknown scenario " + args[1]);
        check(expected.equals(cache.path("mods-info", "owned.data")), "typed missing leaf must route exactly");
        check(!Files.exists(expected), "path lookup must not create a leaf");
        check(!latch.failed(), "valid path must not trip the latch");
        System.out.println("PASS ReferenceCache path checks=3 fixture=" + root);
    }
}
