package com.rustedwarfare.reforge.reference.io;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Three fixed cache types in an already prepared, owned private directory. */
public final class ReferenceCache {
    private static final String[] TYPES = {"mods-info", "mods-dir-search", "mods-dir-music"};
    private final Path root;
    private final PrivatePathPolicy policy;
    private final FatalLatch latch;

    public ReferenceCache(Path cacheRoot, FatalLatch latch) {
        if (latch == null) throw new IllegalArgumentException("A shared fatal latch is required");
        this.latch = latch;
        latch.check();
        try {
            if (cacheRoot == null || !cacheRoot.isAbsolute() || !cacheRoot.toRealPath().equals(cacheRoot))
                throw latch.trip("invalid-root", "A canonical prepared cache root is required", null);
            this.root = cacheRoot;
            this.policy = new PrivatePathPolicy(root);
            ready();
        } catch (PrivatePathPolicy.PathViolation error) {
            throw latch.trip(error.code(), error.getMessage(), error);
        } catch (IOException | SecurityException error) {
            throw latch.trip("io-error", "Cannot inspect the private cache roots", error);
        }
    }

    public synchronized Path path(String type, String encodedKey) {
        latch.check();
        try {
            ready();
            boolean allowed = false;
            for (String candidate : TYPES) if (candidate.equals(type)) allowed = true;
            if (!allowed) throw latch.trip("cache-type", "Unsupported cache type", null);
            if (encodedKey == null || encodedKey.isEmpty() || encodedKey.equals(".") || encodedKey.equals("..")
                    || encodedKey.indexOf('/') >= 0 || encodedKey.indexOf('\\') >= 0
                    || encodedKey.indexOf(':') >= 0 || encodedKey.indexOf('\0') >= 0)
                throw latch.trip("invalid-path", "An encoded single cache leaf is required", null);
            Path requested = root.resolve(type + ".cachedata").resolve(encodedKey);
            Path checked = policy.checkFile(raw(requested), PrivatePathPolicy.Access.EXISTS);
            try {
                BasicFileAttributes attributes = Files.readAttributes(checked, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                requireCanonical(checked);
                if (!attributes.isRegularFile())
                    throw latch.trip("wrong-type", "Cache leaves must be regular files", null);
            } catch (NoSuchFileException missing) {
                policy.checkFile(raw(checked.getParent()), PrivatePathPolicy.Access.LIST);
            }
            return checked;
        } catch (InvalidPathException error) {
            throw latch.trip("invalid-path", "Platform rejected the cache leaf", error);
        } catch (PrivatePathPolicy.PathViolation error) {
            throw latch.trip(error.code(), error.getMessage(), error);
        } catch (IOException | SecurityException | UnsupportedOperationException error) {
            throw latch.trip("io-error", "Cannot route a private cache leaf", error);
        }
    }

    public synchronized FileInputStream open(String type, String encodedKey) {
        latch.check();
        Path checked = path(type, encodedKey);
        try {
            try {
                Files.readAttributes(checked, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                path(type, encodedKey);
                return null;
            }
            Files.setLastModifiedTime(checked, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            checked = path(type, encodedKey);
            FileInputStream stream = new FileInputStream(checked.toFile());
            try { latch.check(); }
            catch (FatalLatch.Failure error) {
                try { stream.close(); }
                catch (IOException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
            return stream;
        } catch (IOException | SecurityException | UnsupportedOperationException error) {
            throw latch.trip("io-error", "Cannot touch and open a private cache leaf", error);
        }
    }

    public synchronized boolean write(String type, String encodedKey, InputStream input) {
        latch.check();
        Path target = path(type, encodedKey);
        if (input == null) throw latch.trip("invalid-path", "A borrowed cache input is required", null);
        Path temporary = path(type, encodedKey + ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (count == 0) {
                        int value = input.read();
                        if (value == -1) break;
                        output.write(value);
                    } else output.write(buffer, 0, count);
                }
            }
            temporary = path(type, encodedKey + ".tmp");
            target = path(type, encodedKey);
            policy.checkFile(raw(temporary), PrivatePathPolicy.Access.READ);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            target = path(type, encodedKey);
            policy.checkFile(raw(target), PrivatePathPolicy.Access.READ);
            try {
                Files.readAttributes(temporary, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                throw latch.trip("io-error", "Cache temporary leaf remained after replacement", null);
            } catch (NoSuchFileException consumed) {
                ready();
            }
            latch.check();
            return true;
        } catch (FatalLatch.Failure error) {
            throw error;
        } catch (PrivatePathPolicy.PathViolation error) {
            throw latch.trip(error.code(), error.getMessage(), error);
        } catch (IOException | RuntimeException error) {
            throw latch.trip("io-error", "Cannot write and replace a private cache leaf", error);
        }
    }

    public synchronized void invalidate(String type, String encodedKey) {
        latch.check();
        Path checked = path(type, encodedKey);
        try {
            Files.deleteIfExists(checked);
            checked = path(type, encodedKey);
            try {
                Files.readAttributes(checked, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                throw latch.trip("io-error", "Invalidated cache leaf still exists", null);
            } catch (NoSuchFileException absent) {
                ready();
            }
            latch.check();
        } catch (IOException | SecurityException | UnsupportedOperationException error) {
            throw latch.trip("io-error", "Cannot invalidate a private cache leaf", error);
        }
    }

    public synchronized int count() {
        latch.check();
        try {
            return leaves().size();
        } catch (PrivatePathPolicy.PathViolation error) {
            throw latch.trip(error.code(), error.getMessage(), error);
        } catch (IOException | SecurityException | UnsupportedOperationException | DirectoryIteratorException error) {
            throw latch.trip("io-error", "Cannot count private cache leaves", error);
        }
    }

    public synchronized int clear() {
        latch.check();
        try {
            List<Path> selected = leaves();
            int deleted = 0;
            for (Path entry : selected) {
                String directory = entry.getParent().getFileName().toString();
                String type = directory.substring(0, directory.length() - ".cachedata".length());
                Path checked = path(type, entry.getFileName().toString());
                Files.delete(checked);
                deleted++;
                System.out.println("CACHE_DELETE " + checked);
            }
            if (!leaves().isEmpty()) throw latch.trip("io-error", "Cache leaves remained after cleanup", null);
            latch.check();
            return deleted;
        } catch (PrivatePathPolicy.PathViolation error) {
            throw latch.trip(error.code(), error.getMessage(), error);
        } catch (IOException | SecurityException | UnsupportedOperationException | DirectoryIteratorException error) {
            throw latch.trip("io-error", "Cannot clear private cache leaves", error);
        }
    }

    private List<Path> leaves() throws IOException {
        ready();
        List<Path> result = new ArrayList<>();
        for (String type : TYPES) {
            Path directory = policy.checkFile(raw(root.resolve(type + ".cachedata")), PrivatePathPolicy.Access.LIST);
            List<Path> selected = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path entry : stream) selected.add(path(type, entry.getFileName().toString()));
            }
            selected.sort(Comparator.comparing(entry -> entry.getFileName().toString()));
            result.addAll(selected);
        }
        return result;
    }

    private void ready() throws IOException {
        policy.checkFile(raw(root), PrivatePathPolicy.Access.LIST);
        for (String type : TYPES) {
            Path directory = root.resolve(type + ".cachedata");
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                throw latch.trip("missing-path", "A fixed cache type disappeared", missing);
            }
            if (attributes.isSymbolicLink())
                throw latch.trip("symbolic-link", "Fixed cache types cannot be links", null);
            if (!attributes.isDirectory())
                throw latch.trip("wrong-type", "Fixed cache types must be directories", null);
            requireCanonical(directory);
        }
    }

    private void requireCanonical(Path path) throws IOException {
        // Windows junctions are not necessarily reported as symbolic links by NIO.
        if (Files.isSymbolicLink(path) || !path.toRealPath().equals(path))
            throw latch.trip("symbolic-link", "Cache paths cannot contain canonical aliases", null);
    }

    private static String raw(Path path) {
        return path.toString().replace('\\', '/');
    }
}
