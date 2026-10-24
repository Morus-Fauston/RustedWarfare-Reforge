package com.rustedwarfare.reforge.reference.boot;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Public cleanup/codec seams only; each case runs in its own JVM. No Context substitutes. */
public final class GbootStateIoTest {
    private static void check(boolean accepted, String message) {
        if (!accepted) throw new AssertionError(message);
    }

    private interface Operation { void run() throws Exception; }

    private static com.rustedwarfare.reforge.reference.io.FatalLatch.Failure failure(String code, Operation action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (com.rustedwarfare.reforge.reference.io.FatalLatch.Failure error) {
            check(code.equals(error.code()), "wrong failure code: " + error.code());
            return error;
        }
    }

    private static final class FailingInput extends FileInputStream {
        int attempts;
        final IOException error = new IOException("input-close-literal");
        FailingInput(File file) throws IOException { super(file); }
        @Override public void close() throws IOException {
            attempts++;
            super.close();
            throw error;
        }
    }

    private static final class CountingOutput extends FileOutputStream {
        int attempts;
        final boolean fail;
        final IOException error = new IOException("raw-close-literal");
        CountingOutput(File file, boolean fail) throws IOException { super(file); this.fail = fail; }
        @Override public void close() throws IOException {
            attempts++;
            super.close();
            if (fail) throw error;
        }
    }

    private static final class FailingWrapper extends DataOutputStream {
        int attempts;
        final IOException error = new IOException("wrapper-close-literal");
        FailingWrapper(FileOutputStream raw) { super(raw); }
        @Override public void close() throws IOException { attempts++; throw error; }
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        Path directory = Paths.get(args[1]).toAbsolutePath();
        Files.createDirectories(directory);
        if (scenario.equals("close-input-normal")) {
            Path path = Files.createTempFile(directory, "owned-input-", ".bin");
            FileInputStream input = new FileInputStream(path.toFile());
            GbootBootstrap.closeStateInput(input, null);
            check(!input.getChannel().isOpen(), "owned raw input must close");
        } else if (scenario.equals("close-output-normal")) {
            Path path = Files.createTempFile(directory, "owned-output-", ".bin");
            FileOutputStream raw = new FileOutputStream(path.toFile());
            DataOutputStream wrapper = new DataOutputStream(raw);
            wrapper.writeInt(0x01020304);
            GbootBootstrap.closeStateOutput(wrapper, raw, null);
            check(!raw.getChannel().isOpen(), "owned raw output must close");
            byte[] bytes = Files.readAllBytes(path);
            check(bytes.length == 4 && bytes[0] == 1 && bytes[3] == 4, "real output bytes must survive cleanup");
        } else if (scenario.equals("codec-accepted")) {
            GbootBootstrap.requireStateDecoded(true);
            GbootBootstrap.requireStateEncoded(true);
        } else if (scenario.equals("decode-failed") || scenario.equals("encode-failed")) {
            boolean decode = scenario.equals("decode-failed");
            com.rustedwarfare.reforge.reference.io.FatalLatch.Failure first = failure(
                decode ? "state-decode-failed" : "state-encode-failed",
                () -> { if (decode) GbootBootstrap.requireStateDecoded(false); else GbootBootstrap.requireStateEncoded(false); });
            com.rustedwarfare.reforge.reference.io.FatalLatch.Failure next = failure(first.code(),
                () -> GbootBootstrap.requireStateDecoded(true));
            check(next == first, "first latch Failure must retain identity");
        } else if (scenario.equals("close-input-failed")) {
            Path path = Files.createTempFile(directory, "failed-input-", ".bin");
            FailingInput input = new FailingInput(path.toFile());
            com.rustedwarfare.reforge.reference.io.FatalLatch.Failure first = failure("io-error",
                () -> GbootBootstrap.closeStateInput(input, null));
            check(input.attempts == 1 && !input.getChannel().isOpen(), "input attempted and resource closed");
            check(first.getCause() == input.error, "input cause exact identity");
            check(failure("io-error", () -> GbootBootstrap.requireStateEncoded(true)) == first, "input close fatal retained");
        } else if (scenario.equals("close-output-both-failed")) {
            Path path = Files.createTempFile(directory, "failed-output-", ".bin");
            CountingOutput raw = new CountingOutput(path.toFile(), true);
            FailingWrapper wrapper = new FailingWrapper(raw);
            com.rustedwarfare.reforge.reference.io.FatalLatch.Failure first = failure("io-error",
                () -> GbootBootstrap.closeStateOutput(wrapper, raw, null));
            check(wrapper.attempts == 1 && raw.attempts == 1, "wrapper then raw both attempted");
            check(!raw.getChannel().isOpen(), "raw resource closed despite wrapper failure");
            check(first.getCause() == wrapper.error, "wrapper first cause retained");
        } else if (scenario.equals("primary-input")) {
            Path path = Files.createTempFile(directory, "primary-input-", ".bin");
            FailingInput input = new FailingInput(path.toFile());
            Throwable primary = new IOException("body-primary-literal");
            GbootBootstrap.closeStateInput(input, primary);
            check(input.attempts == 1 && primary.getSuppressed()[0] == input.error, "primary input retains exact secondary");
            check(failure("io-error", () -> GbootBootstrap.requireStateDecoded(true)).getCause() == input.error, "input cleanup latch cause exact");
        } else if (scenario.equals("primary-output")) {
            Path path = Files.createTempFile(directory, "primary-output-", ".bin");
            CountingOutput raw = new CountingOutput(path.toFile(), true);
            FailingWrapper wrapper = new FailingWrapper(raw);
            Throwable primary = new IllegalStateException("body-primary-literal");
            GbootBootstrap.closeStateOutput(wrapper, raw, primary);
            check(wrapper.attempts == 1 && raw.attempts == 1, "both attempts with body primary");
            check(primary.getSuppressed().length == 2, "both exact secondary failures attached");
            check(primary.getSuppressed()[0] == wrapper.error && primary.getSuppressed()[1] == raw.error,
                  "body primary object contains ordered original causes");
        } else if (scenario.equals("prior-fatal-healthy-close")) {
            com.rustedwarfare.reforge.reference.io.FatalLatch.Failure first = failure("state-decode-failed",
                () -> GbootBootstrap.requireStateDecoded(false));
            Path path = Files.createTempFile(directory, "prior-healthy-", ".bin");
            FileInputStream input = new FileInputStream(path.toFile());
            GbootBootstrap.closeStateInput(input, null);
            check(!input.getChannel().isOpen(), "state healthy close returns despite prior fatal");
            FileOutputStream raw = new FileOutputStream(path.toFile());
            GbootBootstrap.closeStateOutput(new DataOutputStream(raw), raw, null);
            check(!raw.getChannel().isOpen(), "state output healthy close returns despite prior fatal");
            check(failure("state-decode-failed", () -> GbootBootstrap.requireStateEncoded(true)) == first,
                "healthy state cleanup preserves the prior fatal object");
        } else if (scenario.equals("prior-fatal-cleanup")) {
            com.rustedwarfare.reforge.reference.io.FatalLatch.Failure first = failure("state-decode-failed",
                () -> GbootBootstrap.requireStateDecoded(false));
            Path path = Files.createTempFile(directory, "prior-fatal-", ".bin");
            CountingOutput raw = new CountingOutput(path.toFile(), true);
            FailingWrapper wrapper = new FailingWrapper(raw);
            GbootBootstrap.closeStateOutput(wrapper, raw, first);
            check(wrapper.attempts == 1 && raw.attempts == 1, "cleanup cannot gate on prior fatal");
            check(failure(first.code(), () -> GbootBootstrap.requireStateEncoded(true)) == first, "primary firstFailure unchanged");
        } else if (scenario.equals("prior-fatal-normal-close-failed")) {
            com.rustedwarfare.reforge.reference.io.FatalLatch.Failure first = failure("state-decode-failed",
                () -> GbootBootstrap.requireStateDecoded(false));
            Throwable originalCause = first.getCause();
            Path path = Files.createTempFile(directory, "prior-fatal-normal-", ".bin");
            CountingOutput raw = new CountingOutput(path.toFile(), true);
            FailingWrapper wrapper = new FailingWrapper(raw);
            com.rustedwarfare.reforge.reference.io.FatalLatch.Failure actual = failure("state-decode-failed",
                () -> GbootBootstrap.closeStateOutput(wrapper, raw, null));
            check(actual == first && actual.getCause() == originalCause, "first fatal object/code/cause cannot be replaced");
            check(originalCause == null, "literal original decoder failure cause is null");
            check(wrapper.attempts == 1 && raw.attempts == 1, "no-primary cleanup still attempts wrapper and raw");
            check(failure("state-decode-failed", () -> GbootBootstrap.requireStateEncoded(true)) == first,
                  "later public gate preserves original fatal");
        } else if (scenario.equals("input-roots-missing") || scenario.equals("output-roots-missing")
                || scenario.equals("temp-roots-missing")) {
            com.rustedwarfare.reforge.reference.io.FatalLatch.Failure first = failure("roots-not-ready", () -> {
                if (scenario.equals("input-roots-missing")) GbootBootstrap.openStateInput();
                else if (scenario.equals("output-roots-missing")) GbootBootstrap.openStateOutput();
                else GbootBootstrap.createPrivateTempFile(null, null);
            });
            check(failure("roots-not-ready", () -> GbootBootstrap.requireStateDecoded(true)) == first,
                  "state/tmp root-only failure must latch before path/parameter work");
        } else if (scenario.equals("wrapper-absent")) {
            Path path = Files.createTempFile(directory, "absent-wrapper-", ".bin");
            CountingOutput raw = new CountingOutput(path.toFile(), false);
            GbootBootstrap.closeStateOutput(null, raw, null);
            check(raw.attempts == 1 && !raw.getChannel().isOpen(), "raw closes when wrapper construction failed");
        } else {
            throw new IllegalArgumentException("Unknown scenario: " + scenario);
        }
        System.out.println("PASS " + scenario + " (public cleanup/codec only; roots/Context runtime unverified)");
    }
}
