package com.rustedwarfare.reforge.android;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure JDK checks use owned temporary bytes, not packaged game assets. */
public final class SandboxAssetFilesTest {
    private static int checks;

    private static Map<String, byte[]> fixture() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String phase : new String[] {"P001-移动沙盒输入", "P002-知识沙盒输入"}) {
            for (String name : new String[] {"maps/p001-controlled.tmx", "tilesets/p001-ground.tsx",
                    "tilesets/p001-rock.tsx", "units/weaponless_tank.ini"}) {
                String path = "TestLab/local/" + phase + "/" + name;
                files.put(path, ("owned fixture " + path).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        files.put("TestLab/local/P002-知识沙盒输入/sandbox.properties", new byte[] {1, 2});
        files.put("Rusted Warfare/assets/tilesets/bitmaps/longgrass.png", new byte[] {3});
        files.put("Rusted Warfare/assets/tilesets/bitmaps/mountain.png", new byte[] {4});
        files.put("Rusted Warfare/assets/units/tanks/tank.png", new byte[] {5});
        return files;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejected(Path root, Map<String, byte[]> payload) throws Exception {
        try {
            SandboxAssetFiles.install(root, payload);
            throw new AssertionError("Unexpected install success");
        } catch (IOException | IllegalArgumentException expected) { checks++; }
    }

    public static void main(String[] arguments) throws Exception {
        Path temporary = Path.of(arguments[0]).toRealPath();
        Path valid = temporary.resolve("valid/p001");
        Files.createDirectories(valid.getParent());
        Map<String, byte[]> payload = fixture();
        SandboxAssetFiles.install(valid, payload);
        SandboxAssetFiles.install(valid, payload);
        for (Map.Entry<String, byte[]> entry : payload.entrySet())
            require(java.util.Arrays.equals(Files.readAllBytes(valid.resolve(entry.getKey())), entry.getValue()), entry.getKey());
        checks++;

        Path old = temporary.resolve("old/p001");
        Files.createDirectories(old.getParent());
        for (Map.Entry<String, byte[]> entry : payload.entrySet()) {
            if (entry.getKey().contains("P002-")) continue;
            Path target = old.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        SandboxAssetFiles.install(old, payload);
        for (String name : payload.keySet()) require(Files.isRegularFile(old.resolve(name)), name);
        checks++;

        for (String mode : new String[] {"missing", "extra", "wrong-name"}) {
            Map<String, byte[]> wrong = fixture();
            if (!mode.equals("extra")) wrong.remove("TestLab/local/P002-知识沙盒输入/sandbox.properties");
            if (!mode.equals("missing")) wrong.put("extra.bin", new byte[] {9});
            Path destination = temporary.resolve(mode + "/p001");
            Files.createDirectories(destination.getParent());
            rejected(destination, wrong);
            require(!Files.exists(destination), "Invalid payload created output");
        }
        for (String mode : new String[] {"unknown-file", "unknown-directory", "changed-known-file"}) {
            Path destination = temporary.resolve(mode + "/p001");
            Files.createDirectories(destination);
            Path bad = destination.resolve(mode.equals("changed-known-file")
                    ? "TestLab/local/P001-移动沙盒输入/maps/p001-controlled.tmx" : "private-data");
            Files.createDirectories(bad.getParent());
            if (mode.equals("unknown-directory")) Files.createDirectory(bad);
            else Files.write(bad, new byte[] {42});
            rejected(destination, payload);
            require(!Files.exists(destination.resolve("Rusted Warfare")), "Rejected tree was modified");
            require(Files.exists(bad), "Existing data removed");
        }
        for (String mode : new String[] {"root-link", "parent-link", "leaf-link"}) {
            Path directory = temporary.resolve(mode);
            Files.createDirectory(directory);
            Path foreign = temporary.resolve(mode + "-foreign");
            Files.createDirectory(foreign);
            Path destination = directory.resolve("p001");
            if (mode.equals("root-link")) Files.createSymbolicLink(destination, foreign);
            else if (mode.equals("parent-link")) {
                Files.createSymbolicLink(directory.resolve("parent"), foreign);
                destination = directory.resolve("parent/p001");
            } else {
                Files.createDirectory(destination);
                Path leaf = destination.resolve("TestLab/local/P002-知识沙盒输入/sandbox.properties");
                Files.createDirectories(leaf.getParent());
                Path foreignFile = foreign.resolve("owned.bin");
                Files.write(foreignFile, new byte[] {8});
                Files.createSymbolicLink(leaf, foreignFile);
            }
            rejected(destination, payload);
            require(!Files.exists(foreign.resolve("Rusted Warfare")), "Wrote through link");
        }
        System.out.println("PASS " + checks + " fixed-asset host checks; no Android runtime");
    }
}
