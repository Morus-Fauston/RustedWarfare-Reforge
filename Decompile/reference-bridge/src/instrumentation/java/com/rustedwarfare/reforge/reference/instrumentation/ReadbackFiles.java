package com.rustedwarfare.reforge.reference.instrumentation;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/** Checks only the literal anchor leaf and fixed paths below its canonical target. */
final class ReadbackFiles {
    private ReadbackFiles() { }

    static Path anchor(Path literal, List<Object> observations) throws Exception {
        Map<String, Object> r = ReadbackEvidence.map(); observations.add(r);
        directory(literal, r, true); return literal.toRealPath();
    }

    static void fixedParents(Path trust, Path fixedDirectory, List<Object> observations) throws Exception {
        InitialProtocol.require(trust.isAbsolute() && fixedDirectory.isAbsolute() && fixedDirectory.normalize().equals(fixedDirectory)
                && fixedDirectory.startsWith(trust), "private-parent-outside-anchor");
        Path current = trust;
        for (Path name : trust.relativize(fixedDirectory)) {
            current = current.resolve(name); Map<String, Object> r = ReadbackEvidence.map(); observations.add(r); directory(current, r, false);
        }
    }

    static void directory(Path literal, Map<String, Object> r, boolean allowAncestorAliases) throws Exception {
        r.put("literal", literal.toString()); r.put("complete", false);
        BasicFileAttributes attrs = Files.readAttributes(literal, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        r.put("nofollowType", attrs.isSymbolicLink() ? "symbolic-link" : attrs.isDirectory() ? "directory" : attrs.isOther() ? "other" : "not-directory");
        Path canonical = literal.toRealPath(); r.put("canonical", canonical.toString());
        InitialProtocol.require(attrs.isDirectory() && !attrs.isSymbolicLink() && !attrs.isOther(), "private-directory-type:" + literal);
        InitialProtocol.require(allowAncestorAliases || canonical.equals(literal), "private-directory-alias:" + literal);
        r.put("complete", true); r.put("ancestorAliasAllowed", allowAncestorAliases);
    }

    static Map<String, Object> file(Path literal, List<Object> observations, Long expectedBytes, String expectedHash) throws Exception {
        Map<String, Object> r = ReadbackEvidence.map(); observations.add(r); readFile(literal, r, expectedBytes, expectedHash); return r;
    }

    static void readFile(Path literal, Map<String, Object> r, Long expectedBytes, String expectedHash) throws Exception {
        r.put("literal", literal.toString()); r.put("readComplete", false);
        BasicFileAttributes before = Files.readAttributes(literal, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        r.put("nofollowType", before.isSymbolicLink() ? "symbolic-link" : before.isRegularFile() ? "regular-file" : "other");
        r.put("canonical", literal.toRealPath().toString()); r.put("statBytes", before.size());
        InitialProtocol.require(before.isRegularFile() && !before.isSymbolicLink() && literal.toRealPath().equals(literal), "noncanonical-file:" + literal);
        try (InputStream input = Files.newInputStream(literal, LinkOption.NOFOLLOW_LINKS)) { digest(input, r); }
        BasicFileAttributes after = Files.readAttributes(literal, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        boolean stable = after.isRegularFile() && !after.isSymbolicLink() && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime()) && Long.valueOf(after.size()).equals(r.get("bytes"));
        r.put("stableDuringRead", stable); InitialProtocol.require(stable, "file-changed-during-read");
        boolean matched = (expectedBytes == null || expectedBytes.equals(r.get("bytes"))) && (expectedHash == null || expectedHash.equals(r.get("sha256")));
        r.put("matchesExpected", matched); InitialProtocol.require(matched, "file-identity-mismatch:" + literal);
    }

    static void digest(InputStream input, Map<String, Object> r) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[16384]; long count = 0; boolean eof = false;
        try {
            int n; while ((n = input.read(buffer)) != -1) { count += n; InitialProtocol.require(count <= 67108864L, "input-size-limit"); digest.update(buffer, 0, n); }
            eof = true;
        } finally {
            r.put("bytesObserved", count); r.put("readComplete", eof);
            if (eof) { r.put("bytes", count); r.put("sha256", InitialProtocol.hex(digest.digest())); }
            else r.put("partialSha256", InitialProtocol.hex(digest.digest()));
        }
    }
}
