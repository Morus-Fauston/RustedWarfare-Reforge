package com.rustedwarfare.reforge.android;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Installs the fixed internal fixtures without replacing unknown local data. */
public final class SandboxAssetFiles {
    private SandboxAssetFiles() { }

    public static Set<String> paths() {
        Set<String> names = new HashSet<>();
        for (String phase : new String[] {"P001-移动沙盒输入", "P002-知识沙盒输入"})
            for (String name : new String[] {"maps/p001-controlled.tmx", "tilesets/p001-ground.tsx",
                    "tilesets/p001-rock.tsx", "units/weaponless_tank.ini"})
                names.add("TestLab/local/" + phase + "/" + name);
        names.add("TestLab/local/P002-知识沙盒输入/sandbox.properties");
        names.add("Rusted Warfare/assets/tilesets/bitmaps/longgrass.png");
        names.add("Rusted Warfare/assets/tilesets/bitmaps/mountain.png");
        names.add("Rusted Warfare/assets/units/tanks/tank.png");
        return Collections.unmodifiableSet(names);
    }

    public static void install(Path destination, Map<String, byte[]> payload) throws IOException {
        if (!payload.keySet().equals(paths()))
            throw new IllegalArgumentException("Expected the exact twelve sandbox assets");
        if (!destination.isAbsolute() || !destination.normalize().equals(destination))
            throw new IllegalArgumentException("Expected an absolute normalized destination");
        Map<Path, byte[]> files = new LinkedHashMap<>();
        Set<Path> directories = new HashSet<>();
        directories.add(destination);
        for (String name : paths()) {
            byte[] bytes = payload.get(name);
            if (bytes == null) throw new IllegalArgumentException("Missing asset bytes: " + name);
            Path target = destination.resolve(name);
            files.put(target, bytes.clone());
            for (Path parent = target.getParent(); parent.startsWith(destination); parent = parent.getParent())
                directories.add(parent);
        }
        checkPath(destination);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(destination, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    checkPath(directory);
                    if (!directories.contains(directory)) throw new IOException("Unknown sandbox directory: " + directory);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    checkPath(file);
                    byte[] expected = files.get(file);
                    if (!attributes.isRegularFile() || expected == null || !Arrays.equals(Files.readAllBytes(file), expected))
                        throw new IOException("Unknown or changed sandbox file: " + file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        // Complete validation precedes the first write; partial IO failures remain for diagnosis.
        for (Map.Entry<Path, byte[]> entry : files.entrySet()) {
            Path target = entry.getKey();
            checkPath(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
            Files.createDirectories(target.getParent());
            checkPath(target);
            try (java.io.OutputStream stream = Files.newOutputStream(target,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                stream.write(entry.getValue());
            }
        }
    }

    private static void checkPath(Path path) throws IOException {
        for (Path component = path; component != null; component = component.getParent()) {
            if (!Files.exists(component, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attributes = Files.readAttributes(component, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther() || !component.toRealPath().equals(component))
                throw new IOException("Linked or noncanonical sandbox path: " + component);
        }
    }
}
