package com.rustedwarfare.reforge.reference.boot;

import android.content.Context;
import android.net.Uri;
import java.util.HashMap;
import com.rustedwarfare.reforge.reference.io.FatalLatch;
import com.rustedwarfare.reforge.reference.io.PrivatePathPolicy;
import com.rustedwarfare.reforge.reference.io.ReferenceIo;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/** Controlled static-patch bootstrap. Context behavior requires a later real APP probe. */
public final class GbootBootstrap {
    private static final FatalLatch LATCH = new FatalLatch();
    private static Context application;
    private static Path filesRoot;
    private static Path root;
    private static Path[] directories;
    private static ReferenceIo io;
    private static boolean backendPublished;

    private GbootBootstrap() { }

    public static void init(Context context) {
        LATCH.check();
        try {
            if (context == null) throw LATCH.trip("invalid-context", "Context is required", null);
            Context candidateContext = context.getApplicationContext();
            if (candidateContext == null)
                throw LATCH.trip("invalid-context", "ApplicationContext is required", null);
            File files = candidateContext.getFilesDir();
            if (files == null || !files.isAbsolute())
                throw LATCH.trip("invalid-root", "An absolute files directory is required", null);
            Path candidateFiles = files.toPath().toRealPath();
            if (io != null) {
                if (application != candidateContext || !filesRoot.equals(candidateFiles))
                    throw LATCH.trip("context-mismatch", "ApplicationContext or files root changed", null);
                requireRootsReady();
                return;
            }
            PrivatePathPolicy filesPolicy = new PrivatePathPolicy(candidateFiles);
            Path candidateRoot = prepareDirectory(filesPolicy, candidateFiles.resolve("reference-test"));
            PrivatePathPolicy rootPolicy = new PrivatePathPolicy(candidateRoot);
            String[] names = {"maps", "units", "saves", "replays", "cache", "bridge"};
            Path[] candidateDirectories = new Path[names.length];
            for (int index = 0; index < names.length; index++)
                candidateDirectories[index] = prepareDirectory(rootPolicy, candidateRoot.resolve(names[index]));
            ReferenceIo candidateIo = new ReferenceIo(candidateRoot, candidateDirectories[5], LATCH);
            // Publish only after all fixed directories and the frozen PP/RP constructor succeed.
            application = candidateContext;
            filesRoot = candidateFiles;
            root = candidateRoot;
            directories = candidateDirectories;
            io = candidateIo;
        } catch (PrivatePathPolicy.PathViolation error) {
            throw LATCH.trip(error.code(), error.getMessage(), error);
        } catch (InvalidPathException error) {
            throw LATCH.trip("invalid-path", "Platform rejected a bootstrap path", error);
        } catch (IOException | SecurityException error) {
            throw LATCH.trip("io-error", "Cannot prepare canonical private roots", error);
        }
    }

    private static Path prepareDirectory(PrivatePathPolicy policy, Path requested)
            throws IOException {
        Path checked = policy.checkFile(requested.toString(), PrivatePathPolicy.Access.EXISTS);
        try {
            Files.readAttributes(checked, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            try { Files.createDirectory(checked); }
            catch (FileAlreadyExistsException raced) {
                // Accept only if the same full post-creation checks below succeed.
            }
        }
        Path directory = policy.checkFile(checked.toString(), PrivatePathPolicy.Access.LIST);
        Path real = directory.toRealPath();
        if (!directory.equals(real))
            throw new PrivatePathPolicy.PathViolation("outside-root", "Prepared directory changed canonical name");
        return real;
    }

    public static void requireRootsReady() {
        LATCH.check();
        if (io == null) throw LATCH.trip("roots-not-ready", "Private roots are not initialized", null);
        try {
            io.existingDirectory(root.toString());
            for (Path directory : directories) io.existingDirectory(directory.toString());
        } catch (SecurityException error) {
            throw LATCH.trip("io-error", "Cannot inspect private roots", error);
        }
    }

    public static void requireBackendPublished() {
        LATCH.check();
        requireRootsReady();
        if (!backendPublished)
            throw LATCH.trip("backend-not-published", "Original backend has not been published", null);
    }

    /** Caller must invoke only after the original backend field has been written. */
    public static void markBackendPublished() {
        LATCH.check();
        requireRootsReady();
        backendPublished = true;
    }

    public static String privateRoot() {
        LATCH.check();
        requireRootsReady();
        return root.toString();
    }

    public static String privateRootWithSlash() {
        LATCH.check();
        return privateRoot() + "/";
    }

    public static String normalizePrivateRaw(String path) {
        LATCH.check();
        requireBackendPublished();
        if (path == null || path.trim().isEmpty() || path.indexOf('\0') >= 0
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0 || !path.startsWith("/"))
            throw LATCH.trip("invalid-path", "An unambiguous private absolute path is required", null);
        rejectReadRoute(path);
        String candidate = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (String component : candidate.substring(1).split("/", -1))
            if (component.isEmpty() || component.equals(".") || component.equals(".."))
                throw LATCH.trip("invalid-path", "Ambiguous private path component", null);
        try {
            Path parsed = Paths.get(candidate);
            if (!parsed.startsWith(root))
                throw LATCH.trip("outside-root", "Path is outside the private root", null);
            return parsed.toString();
        } catch (InvalidPathException error) {
            throw LATCH.trip("invalid-path", "Platform rejected a private path", error);
        }
    }

    public static long privateLastModified(String path) {
        LATCH.check();
        String candidate = normalizePrivateRaw(path);
        return io.lastModified(candidate);
    }

    private static String checked(String path, PrivatePathPolicy.Access access) {
        LATCH.check();
        String candidate = normalizePrivateRaw(path);
        try { return io.privateFile(candidate, access).toString(); }
        catch (SecurityException error) {
            throw LATCH.trip("io-error", "Cannot inspect private path", error);
        }
    }

    public static String checkPrivateRead(String path) {
        LATCH.check();
        return checked(path, PrivatePathPolicy.Access.READ);
    }

    public static String checkPrivateWrite(String path) {
        LATCH.check();
        return checked(path, PrivatePathPolicy.Access.WRITE);
    }

    public static String checkPrivateExists(String path) {
        LATCH.check();
        return checked(path, PrivatePathPolicy.Access.EXISTS);
    }

    public static String checkPrivateDirectory(String path) {
        LATCH.check();
        return checked(path, PrivatePathPolicy.Access.LIST);
    }

    public static String checkAssetRead(String name) {
        LATCH.check();
        requireBackendPublished();
        return io.asset(assetInput(name, false), PrivatePathPolicy.Access.READ);
    }

    /** Explicit read route; classification never probes a target or supplies a fallback. */
    public static boolean isAssetPath(String path) {
        LATCH.check();
        requireBackendPublished();
        if (path != null && path.startsWith("/")) {
            normalizePrivateRaw(path);
            return false;
        }
        io.asset(assetInput(path, true), PrivatePathPolicy.Access.EXISTS);
        return true;
    }

    private static void rejectReadRoute(String path) {
        if (path.contains(".[saflink]") || path.contains("/saf-virtual/")
                || path.contains("MOD|") || path.contains("NEW_PATH|"))
            throw LATCH.trip("unsupported-route", "Mapped, SAF and mod aliases are forbidden", null);
        for (String part : path.split("/", -1)) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".rwmod") || lower.endsWith(".zip"))
                throw LATCH.trip("unsupported-route", "Archives and archive members are forbidden", null);
        }
    }

    private static String assetInput(String name, boolean allowTrailingSlash) {
        if (name == null || name.trim().isEmpty() || name.indexOf('\0') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.startsWith("/"))
            throw LATCH.trip("invalid-path", "An unambiguous relative asset name is required", null);
        rejectReadRoute(name);
        // Strip only a permitted directory suffix here; RP alone strips one assets/ prefix.
        return allowTrailingSlash && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }

    public static String checkAssetList(String name) {
        LATCH.check();
        requireBackendPublished();
        return io.asset(assetInput(name, true), PrivatePathPolicy.Access.LIST);
    }

    public static String checkAssetExists(String name) {
        LATCH.check();
        requireBackendPublished();
        return io.asset(assetInput(name, true), PrivatePathPolicy.Access.EXISTS);
    }

    /** NoSuch target is a legal missing leaf only after RP has checked the parent and route. */
    private static BasicFileAttributes privateAttributes(String path) {
        LATCH.check();
        Path canonical = Paths.get(checkPrivateExists(path));
        try {
            BasicFileAttributes attributes = Files.readAttributes(canonical,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink())
                throw LATCH.trip("symbolic-link", "Private metadata target became a link", null);
            // Recheck after metadata, including canonical root and each existing component.
            io.privateFile(canonical.toString(), PrivatePathPolicy.Access.EXISTS);
            return attributes;
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException | SecurityException error) {
            throw LATCH.trip("io-error", "Cannot inspect private metadata", error);
        }
    }

    public static boolean privateExists(String path) {
        LATCH.check();
        return privateAttributes(path) != null;
    }

    public static boolean privateIsDirectory(String path) {
        LATCH.check();
        BasicFileAttributes attributes = privateAttributes(path);
        return attributes != null && attributes.isDirectory();
    }

    public static String checkPrivateList(String path) {
        LATCH.check();
        String canonical = checkPrivateExists(path);
        BasicFileAttributes attributes = privateAttributes(canonical);
        if (attributes == null || !attributes.isDirectory()) return null;
        return checkPrivateDirectory(canonical);
    }

    public static String checkPreparedDirectory(String path) {
        LATCH.check();
        Path canonical = Paths.get(normalizePrivateRaw(path));
        boolean prepared = canonical.equals(root);
        for (Path directory : directories) prepared |= canonical.equals(directory);
        if (!prepared)
            throw LATCH.trip("unsupported-io-operation", "Only fixed prepared directories are allowed", null);
        return checkPrivateDirectory(canonical.toString());
    }

    public static String preferencesPath() {
        LATCH.check();
        requireRootsReady();
        Path canonical;
        try { canonical = io.privateFile(directories[2].resolve("preferences.ini").toString(), PrivatePathPolicy.Access.EXISTS); }
        catch (SecurityException error) { throw LATCH.trip("io-error", "Cannot resolve preferences path", error); }
        try {
            BasicFileAttributes attributes = Files.readAttributes(canonical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile())
                throw LATCH.trip(attributes.isSymbolicLink() ? "symbolic-link" : "wrong-type", "Preferences requires a regular file", null);
        } catch (NoSuchFileException missing) {
            io.existingDirectory(directories[2].toString());
        } catch (IOException | SecurityException error) {
            throw LATCH.trip("io-error", "Cannot inspect preferences path", error);
        }
        return canonical.toString();
    }

    public static boolean preferencesExists() {
        LATCH.check();
        requireRootsReady();
        Path canonical = Paths.get(preferencesPath());
        try {
            BasicFileAttributes attributes = Files.readAttributes(canonical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile())
                throw LATCH.trip(attributes.isSymbolicLink() ? "symbolic-link" : "wrong-type", "Preferences requires a regular file", null);
            return true;
        } catch (NoSuchFileException missing) {
            io.existingDirectory(directories[2].toString());
            return false;
        } catch (IOException | SecurityException error) {
            throw LATCH.trip("io-error", "Cannot inspect preferences existence", error);
        }
    }

    public static String checkIniPrivateRead(String path) {
        LATCH.check();
        requireRootsReady();
        if (path == null || path.trim().isEmpty() || !path.startsWith("/") || path.indexOf(':') >= 0
                || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0)
            throw LATCH.trip("invalid-path", "An absolute private INI child is required", null);
        rejectReadRoute(path);
        for (String part : path.substring(1).split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw LATCH.trip("invalid-path", "Ambiguous INI path component", null);
        try {
            Path candidate = Paths.get(path);
            if (candidate.equals(root) || !candidate.startsWith(root))
                throw LATCH.trip("outside-root", "INI requires a private child", null);
            return io.privateFile(candidate.toString(), PrivatePathPolicy.Access.READ).toString();
        } catch (InvalidPathException error) {
            throw LATCH.trip("invalid-path", "Invalid INI path", error);
        } catch (SecurityException error) {
            throw LATCH.trip("io-error", "Cannot inspect INI input", error);
        }
    }

    public static PrintWriter openPreferencesWriter() {
        LATCH.check();
        requireRootsReady();
        try {
            Path canonical = io.privateFile(directories[2].resolve("preferences.ini").toString(), PrivatePathPolicy.Access.WRITE);
            return new PrintWriter(canonical.toFile());
        } catch (IOException | SecurityException error) {
            throw LATCH.trip("io-error", "Cannot open preferences writer", error);
        }
    }

    public static void requirePreferencesWriterOk(PrintWriter writer) {
        LATCH.check();
        if (writer == null) throw LATCH.trip("invalid-path", "An owned preferences writer is required", null);
        if (writer.checkError())
            throw LATCH.trip("preferences-write-failed", "Preferences writer reported an error", null);
    }

    public static void closePreferencesWriter(PrintWriter writer, Throwable primary) {
        // Cleanup must release before consulting an already-tripped session latch.
        RuntimeException closeError = null;
        boolean trouble = false;
        if (writer != null) {
            try { writer.close(); }
            catch (RuntimeException error) { closeError = error; }
            try { trouble = writer.checkError(); }
            catch (RuntimeException error) {
                if (closeError == null) closeError = error;
                else if (closeError != error) closeError.addSuppressed(error);
            }
        }
        FatalLatch.Failure failure = null;
        if (closeError != null) failure = LATCH.trip("io-error", "Cannot close preferences writer", closeError);
        else if (trouble) failure = LATCH.trip("preferences-write-failed", "Preferences writer reported a close error", null);
        if (primary != null) {
            Throwable secondary = closeError != null ? closeError : failure;
            if (secondary != null && secondary != primary) primary.addSuppressed(secondary);
            return;
        }
        LATCH.check();
    }

    public static String unitsRoot() {
        LATCH.check();
        requireRootsReady();
        return io.existingDirectory(directories[1].toString()).toString();
    }

    public static String mapsRoot() {
        LATCH.check();
        requireRootsReady();
        return io.existingDirectory(directories[0].toString()).toString();
    }

    public static FileInputStream openStateInput() {
        LATCH.check();
        requireRootsReady();
        Path fixed = directories[2].resolve("rtsSave");
        try {
            Path canonical = io.privateFile(fixed.toString(), PrivatePathPolicy.Access.EXISTS);
            try {
                Files.readAttributes(canonical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                // Recheck the already-existing parent: only a missing legal leaf is an empty state.
                io.existingDirectory(directories[2].toString());
                return null;
            }
            Path readable = io.privateFile(canonical.toString(), PrivatePathPolicy.Access.READ);
            return new FileInputStream(readable.toFile());
        } catch (IOException | SecurityException error) {
            throw LATCH.trip("io-error", "Cannot open private state input", error);
        }
    }

    public static FileOutputStream openStateOutput() {
        LATCH.check();
        requireRootsReady();
        try {
            Path writable = io.privateFile(directories[2].resolve("rtsSave").toString(),
                    PrivatePathPolicy.Access.WRITE);
            return new FileOutputStream(writable.toFile(), false);
        } catch (IOException | SecurityException error) {
            throw LATCH.trip("io-error", "Cannot open private state output", error);
        }
    }

    public static File createPrivateTempFile(String prefix, String suffix) throws IOException {
        LATCH.check();
        requireRootsReady();
        try {
            Path cache = io.existingDirectory(directories[4].toString());
            File created;
            try { created = File.createTempFile(prefix, suffix, cache.toFile()); }
            catch (IOException error) {
                LATCH.trip("io-error", "Cannot create private temporary file", error);
                throw error;
            }
            Path writable = io.privateFile(created.getAbsolutePath(), PrivatePathPolicy.Access.WRITE);
            return writable.toFile();
        } catch (SecurityException error) {
            throw LATCH.trip("io-error", "Cannot access private temporary directory", error);
        }
    }

    public static void requireStateDecoded(boolean accepted) {
        LATCH.check();
        if (!accepted) throw LATCH.trip("state-decode-failed", "Original state decoder rejected input", null);
    }

    public static void requireStateEncoded(boolean accepted) {
        LATCH.check();
        if (!accepted) throw LATCH.trip("state-encode-failed", "Original state encoder rejected output", null);
    }

    public static void closeStateInput(FileInputStream input, Throwable primary) {
        IOException failure = null;
        try { if (input != null) input.close(); }
        catch (IOException error) { failure = error; }
        if (failure != null) finishStateClose(primary, failure, null);
    }

    public static void closeStateOutput(DataOutputStream output, FileOutputStream raw, Throwable primary) {
        IOException first = null;
        IOException second = null;
        try { if (output != null) output.close(); }
        catch (IOException error) { first = error; }
        try { if (raw != null) raw.close(); }
        catch (IOException error) {
            if (first == null) first = error;
            else second = error;
        }
        if (first != null) finishStateClose(primary, first, second);
    }

    public static void requireProviderAuthorityMetadata(String authority) {
        LATCH.check();
        if (!"com.corrodinggames.rts.fileProvider".equals(authority))
            throw LATCH.trip("invalid-provider-authority", "Unexpected provider authority", null);
    }

    public static File providerRootFile() {
        LATCH.check();
        requireRootsReady();
        return io.existingDirectory(root.toString()).toFile();
    }

    public static void requireProviderRootMap(HashMap roots, File checkedRoot) {
        LATCH.check();
        if (roots == null || checkedRoot == null || roots.size() != 1
                || !(roots.get("external_files") instanceof File)
                || !((File) roots.get("external_files")).getPath().equals(checkedRoot.getPath()))
            throw LATCH.trip("provider-root-mismatch", "Provider root fields do not match the checked private root", null);
    }

    public static File checkProviderFile(File file) {
        LATCH.check();
        requireRootsReady();
        if (file == null || !file.isAbsolute())
            throw LATCH.trip("invalid-path", "An absolute provider file is required", null);
        String name = file.getPath();
        if (name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0)
            throw LATCH.trip("invalid-path", "Ambiguous provider file path", null);
        rejectReadRoute(name);
        for (String component : name.substring(1).split("/", -1))
            if (component.isEmpty() || component.equals(".") || component.equals(".."))
                throw LATCH.trip("invalid-path", "Ambiguous provider component", null);
        try {
            Path candidate = Paths.get(name);
            if (candidate.equals(root) || !candidate.startsWith(root))
                throw LATCH.trip("outside-root", "Provider requires a private child, not the root", null);
            return io.privateFile(candidate.toString(), PrivatePathPolicy.Access.EXISTS).toFile();
        } catch (InvalidPathException error) {
            throw LATCH.trip("invalid-path", "Invalid provider file path", error);
        } catch (SecurityException error) {
            throw LATCH.trip("io-error", "Cannot inspect provider path", error);
        }
    }

    public static File providerFileForUri(Uri uri, String strategyAuthority) {
        LATCH.check();
        requireRootsReady();
        requireProviderAuthorityMetadata(strategyAuthority);
        if (uri == null || !uri.isHierarchical() || !"content".equals(uri.getScheme())
                || !strategyAuthority.equals(uri.getEncodedAuthority())
                || uri.getEncodedQuery() != null || uri.getEncodedFragment() != null)
            throw LATCH.trip("invalid-path", "Unexpected provider URI route", null);
        String encoded = uri.getEncodedPath();
        String prefix = "/external_files/";
        if (encoded == null || !encoded.startsWith(prefix) || encoded.length() == prefix.length())
            throw LATCH.trip("invalid-path", "Provider URI requires a tagged private child", null);
        for (int index = 0; index < encoded.length(); index++) {
            if (encoded.charAt(index) != '%') continue;
            if (index + 2 >= encoded.length())
                throw LATCH.trip("invalid-path", "Malformed provider URI escape", null);
            int high = Character.digit(encoded.charAt(index + 1), 16);
            int low = Character.digit(encoded.charAt(index + 2), 16);
            if (high < 0 || low < 0 || ((high << 4) | low) == '/' || ((high << 4) | low) == '\\')
                throw LATCH.trip("invalid-path", "Encoded separator in provider URI", null);
            index += 2;
        }
        String relative = Uri.decode(encoded.substring(prefix.length()));
        if (relative.isEmpty() || relative.startsWith("/") || relative.indexOf('%') >= 0
                || relative.indexOf('\0') >= 0 || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0)
            throw LATCH.trip("invalid-path", "Ambiguous decoded provider URI", null);
        rejectReadRoute(relative);
        for (String component : relative.split("/", -1))
            if (component.isEmpty() || component.equals(".") || component.equals(".."))
                throw LATCH.trip("invalid-path", "Ambiguous provider URI component", null);
        return checkProviderFile(root.resolve(relative).toFile());
    }

    public static File checkProviderOpenFile(File file, String mode) {
        LATCH.check();
        requireRootsReady();
        File canonical = checkProviderFile(file);
        boolean read = "r".equals(mode);
        boolean write = "w".equals(mode) || "wt".equals(mode) || "wa".equals(mode)
                || "rw".equals(mode) || "rwt".equals(mode);
        if (!read && !write) throw new IllegalArgumentException("Invalid mode: " + mode);
        Path checked = io.privateFile(canonical.getPath(), read ? PrivatePathPolicy.Access.READ : PrivatePathPolicy.Access.WRITE);
        if ("rw".equals(mode) || "rwt".equals(mode)) {
            try {
                Files.readAttributes(checked, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                checked = io.privateFile(checked.toString(), PrivatePathPolicy.Access.READ);
            } catch (NoSuchFileException missing) {
                io.existingDirectory(checked.getParent().toString());
            } catch (IOException | SecurityException error) {
                throw LATCH.trip("io-error", "Cannot inspect provider read-write file", error);
            }
        }
        return checked.toFile();
    }

    public static long providerLength(File file) {
        LATCH.check();
        requireRootsReady();
        Path checked = checkProviderFile(file).toPath();
        try {
            BasicFileAttributes attributes = Files.readAttributes(checked, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile())
                throw LATCH.trip(attributes.isSymbolicLink() ? "symbolic-link" : "wrong-type", "Provider size requires a regular file", null);
            io.privateFile(checked.toString(), PrivatePathPolicy.Access.READ);
            return attributes.size();
        } catch (NoSuchFileException missing) {
            io.existingDirectory(checked.getParent().toString());
            return 0L;
        } catch (IOException | SecurityException error) {
            throw LATCH.trip("io-error", "Cannot inspect provider length", error);
        }
    }

    public static void denyProviderDelete() {
        LATCH.check();
        requireRootsReady();
        throw LATCH.trip("unsupported-io-operation", "Provider deletion is forbidden", null);
    }

    public static RuntimeException unsupportedRouteFailure(String operation) {
        LATCH.check();
        return LATCH.trip("unsupported-route", "Unsupported controlled IO operation", null);
    }

    public static void rejectUnsupportedIoRoute(String path) {
        LATCH.check();
        if (path != null && (path.contains(".rwmod/") || path.contains(".rwmod\\") || path.endsWith(".rwmod")
                || path.contains(".[saflink]/") || path.contains(".[saflink]\\") || path.endsWith(".[saflink]")
                || path.contains("content:") || path.contains("/saf-virtual/")
                || path.contains("MOD|") || path.contains("NEW_PATH|")))
            throw unsupportedRouteFailure("dispatcher-virtual-route");
        if (path == null || path.trim().isEmpty() || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0)
            throw LATCH.trip("invalid-path", "Invalid ordinary IO route", null);
    }

    private static void finishStateClose(Throwable primary, IOException first, IOException second) {
        // Cleanup deliberately has no latch/roots/backend gate.
        FatalLatch.Failure failure = LATCH.trip("io-error", "Cannot close private state stream", first);
        if (primary != null) {
            if (primary != first) primary.addSuppressed(first);
            if (second != null && primary != second) primary.addSuppressed(second);
            return;
        }
        if (second != null) failure.addSuppressed(second);
        throw failure;
    }
}

