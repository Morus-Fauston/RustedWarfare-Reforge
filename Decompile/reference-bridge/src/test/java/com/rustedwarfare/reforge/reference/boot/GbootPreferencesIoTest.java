package com.rustedwarfare.reforge.reference.boot;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.io.IOException;
import com.rustedwarfare.reforge.reference.io.FatalLatch;

/** Public real PrintWriter seams; run every scenario in a fresh JVM. No fake Context. */
public final class GbootPreferencesIoTest {
    private static void check(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }
    private interface Action { void run(); }
    private static FatalLatch.Failure denied(String code, Action action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (FatalLatch.Failure failure) { check(code.equals(failure.code()), "literal failure code"); return failure; }
    }
    private static final class OwnedWriter extends Writer {
        final String mode;
        int closes;
        final IOException io = new IOException("literal-" + "io-error");
        final RuntimeException runtime = new IllegalStateException("literal-close-runtime");
        OwnedWriter(String mode) { this.mode = mode; }
        public void write(char[] chars, int offset, int length) throws IOException { if (mode.equals("write")) throw io; }
        public void flush() throws IOException { if (mode.equals("flush")) throw io; }
        public void close() throws IOException { closes++; if (mode.equals("close")) throw io; if (mode.equals("runtime")) throw runtime; }
    }
    public static void main(String[] args) {
        if (args[0].equals("healthy")) {
            StringWriter text = new StringWriter();
            PrintWriter writer = new PrintWriter(text);
            writer.println("[settings]");
            writer.println("k:v");
            GbootBootstrap.requirePreferencesWriterOk(writer);
            GbootBootstrap.closePreferencesWriter(writer, null);
            check(text.toString().equals("[settings]" + System.lineSeparator() + "k:v" + System.lineSeparator()), "literal output retained");
        } else if (args[0].equals("write") || args[0].equals("flush")) {
            OwnedWriter raw = new OwnedWriter(args[0]);
            PrintWriter writer = new PrintWriter(raw);
            writer.println("literal");
            FatalLatch.Failure first = denied("preferences-write-failed", () -> GbootBootstrap.requirePreferencesWriterOk(writer));
            check(first == denied(first.code(), () -> GbootBootstrap.requirePreferencesWriterOk(null)), "first failure identity");
        } else if (args[0].equals("close") || args[0].equals("runtime")) {
            OwnedWriter raw = new OwnedWriter(args[0]);
            PrintWriter writer = new PrintWriter(raw);
            GbootBootstrap.requirePreferencesWriterOk(writer);
            FatalLatch.Failure first = denied(args[0].equals("close") ? "preferences-write-failed" : "io-error",
                () -> GbootBootstrap.closePreferencesWriter(writer, null));
            check(raw.closes == 1, "owned close attempted once");
            if (args[0].equals("runtime")) check(first.getCause() == raw.runtime, "close runtime cause same instance");
        } else if (args[0].startsWith("primary-")) {
            OwnedWriter raw = new OwnedWriter(args[0].substring(8));
            PrintWriter writer = new PrintWriter(raw);
            Throwable primary = new IllegalArgumentException("literal-body-primary");
            GbootBootstrap.closePreferencesWriter(writer, primary);
            check(raw.closes == 1 && primary.getSuppressed().length == 1, "cleanup returns and attaches secondary");
            if (args[0].equals("primary-runtime")) check(primary.getSuppressed()[0] == raw.runtime, "exact secondary runtime");
            check(primary.getMessage().equals("literal-body-primary"), "primary unchanged");
        } else if (args[0].startsWith("prior-")) {
            FatalLatch.Failure first = (FatalLatch.Failure) GbootBootstrap.unsupportedRouteFailure("literal-prior");
            OwnedWriter raw = new OwnedWriter(args[0].equals("prior-close") ? "close" : "healthy");
            PrintWriter writer = new PrintWriter(raw);
            check(denied("unsupported-route", () -> GbootBootstrap.closePreferencesWriter(writer, null)) == first, "prior Failure identity after release");
            check(raw.closes == 1, "prior failure cannot block cleanup");
            check(denied("unsupported-route", () -> GbootBootstrap.requirePreferencesWriterOk(null)) == first, "require keeps first identity");
        } else if (args[0].equals("null-writer")) {
            GbootBootstrap.closePreferencesWriter(null, null);
            denied("invalid-path", () -> GbootBootstrap.requirePreferencesWriterOk(null));
        } else if (args[0].startsWith("root-")) {
            FatalLatch.Failure first = denied("roots-not-ready", () -> {
                if (args[0].equals("root-path")) GbootBootstrap.preferencesPath();
                else if (args[0].equals("root-exists")) GbootBootstrap.preferencesExists();
                else if (args[0].equals("root-read")) GbootBootstrap.checkIniPrivateRead(null);
                else if (args[0].equals("root-open")) GbootBootstrap.openPreferencesWriter();
                else throw new AssertionError(args[0]);
            });
            check(denied("roots-not-ready", () -> GbootBootstrap.requirePreferencesWriterOk(null)) == first, "root first priority identity");
        } else throw new IllegalArgumentException(args[0]);
        System.out.println("PASS " + args[0] + " (host Writer only; initialized roots runtime unverified)");
    }
}
