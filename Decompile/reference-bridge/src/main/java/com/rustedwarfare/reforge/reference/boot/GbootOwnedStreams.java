package com.rustedwarfare.reforge.reference.boot;

import com.rustedwarfare.reforge.reference.io.FatalLatch;
import com.rustedwarfare.reforge.reference.io.PrivatePathPolicy;
import com.rustedwarfare.reforge.reference.io.ReferenceIo;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Owned state/preferences streams. The bridge alone owns roots and the one session latch. */
final class GbootOwnedStreams {
    private final FatalLatch latch;
    GbootOwnedStreams(FatalLatch latch) { this.latch = latch; }

    PrintWriter openPreferencesWriter(ReferenceIo io, Path saves) {
        try {
            Path canonical = io.privateFile(saves.resolve("preferences.ini").toString(), PrivatePathPolicy.Access.WRITE);
            return new PrintWriter(canonical.toFile());
        } catch (IOException | SecurityException error) {
            throw latch.trip("io-error", "Cannot open preferences writer", error);
        }
    }

    void requirePreferencesWriterOk(PrintWriter writer) {
        latch.check();
        if (writer == null) throw latch.trip("invalid-path", "An owned preferences writer is required", null);
        if (writer.checkError())
            throw latch.trip("preferences-write-failed", "Preferences writer reported an error", null);
    }

    void closePreferencesWriter(PrintWriter writer, Throwable primary) {
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
        if (closeError != null) failure = latch.trip("io-error", "Cannot close preferences writer", closeError);
        else if (trouble) failure = latch.trip("preferences-write-failed", "Preferences writer reported a close error", null);
        if (primary != null) {
            Throwable secondary = closeError != null ? closeError : failure;
            if (secondary != null && secondary != primary) primary.addSuppressed(secondary);
            return;
        }
        latch.check();
    }

    FileInputStream openStateInput(ReferenceIo io, Path saves) {
        Path fixed = saves.resolve("rtsSave");
        try {
            Path canonical = io.privateFile(fixed.toString(), PrivatePathPolicy.Access.EXISTS);
            try {
                Files.readAttributes(canonical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                // Recheck the already-existing parent: only a missing legal leaf is an empty state.
                io.existingDirectory(saves.toString());
                return null;
            }
            Path readable = io.privateFile(canonical.toString(), PrivatePathPolicy.Access.READ);
            return new FileInputStream(readable.toFile());
        } catch (IOException | SecurityException error) {
            throw latch.trip("io-error", "Cannot open private state input", error);
        }
    }

    FileOutputStream openStateOutput(ReferenceIo io, Path saves) {
        try {
            Path writable = io.privateFile(saves.resolve("rtsSave").toString(),
                    PrivatePathPolicy.Access.WRITE);
            return new FileOutputStream(writable.toFile(), false);
        } catch (IOException | SecurityException error) {
            throw latch.trip("io-error", "Cannot open private state output", error);
        }
    }

    void requireStateDecoded(boolean accepted) {
        latch.check();
        if (!accepted) throw latch.trip("state-decode-failed", "Original state decoder rejected input", null);
    }

    void requireStateEncoded(boolean accepted) {
        latch.check();
        if (!accepted) throw latch.trip("state-encode-failed", "Original state encoder rejected output", null);
    }

    void closeStateInput(FileInputStream input, Throwable primary) {
        IOException failure = null;
        try { if (input != null) input.close(); }
        catch (IOException error) { failure = error; }
        if (failure != null) finishStateClose(primary, failure, null);
    }

    void closeStateOutput(DataOutputStream output, FileOutputStream raw, Throwable primary) {
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

    private void finishStateClose(Throwable primary, IOException first, IOException second) {
        // Cleanup deliberately has no latch/roots/backend gate.
        FatalLatch.Failure failure = latch.trip("io-error", "Cannot close private state stream", first);
        if (primary != null) {
            if (primary != first) primary.addSuppressed(first);
            if (second != null && primary != second) primary.addSuppressed(second);
            return;
        }
        if (second != null) failure.addSuppressed(second);
        throw failure;
    }
}
