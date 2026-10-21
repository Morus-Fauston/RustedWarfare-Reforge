package com.rustedwarfare.reforge.reference.boot;

import com.rustedwarfare.reforge.reference.io.FatalLatch;
import java.io.*;
import java.security.MessageDigest;
import java.util.Base64;

/** Cold JVM public diagnostic boundary checks, with no original game runtime. */
public final class DiagnosticReportBlockTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("retention")) {
            retention();
            return;
        }
        if (args.length > 0 && args[0].equals("concurrent")) {
            concurrent();
            return;
        }
        if (args.length > 0 && args[0].startsWith("failed-output")) {
            failedOutput(args[0].equals("failed-output-throws"));
            return;
        }
        try {
            DiagnosticReportBlock.reject("detectedANR", "literal first ANR");
            throw new AssertionError("diagnostic path returned without fatal");
        } catch (FatalLatch.Failure failure) {
            if (!"unsupported-route".equals(failure.code()))
                throw new AssertionError("wrong fatal code", failure);
        }
        System.out.println("PASS diagnostic fatal");
    }

    private static void retention() throws Exception {
        StringBuilder text = new StringBuilder("first\n\u0000\ud800");
        for (int i = 0; i < 6000; i++) text.append((char) ('a' + i % 26));
        ByteArrayOutputStream local = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(local, true, "UTF-8"));
        FatalLatch.Failure first = null;
        try {
            try { DiagnosticReportBlock.reject(null, text.toString()); }
            catch (FatalLatch.Failure error) { first = error; }
            try { DiagnosticReportBlock.reject("second", "must not replace first"); }
            catch (FatalLatch.Failure error) {
                if (first != error) throw new AssertionError("first fatal object replaced");
            }
        } finally { System.setErr(previous); }
        String[] lines = local.toString("UTF-8").split("\\r?\\n");
        if (lines.length < 3) throw new AssertionError("missing complete local diagnostic");
        String[] start = lines[0].split(" ");
        if (!"RWR_NA_BEGIN".equals(start[0])) throw new AssertionError("missing begin");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 1; i < lines.length - 1; i++) {
            String[] row = lines[i].split(" ");
            if (!"RWR_NA_CHUNK".equals(row[0]) || Integer.parseInt(row[1]) != i - 1
                    || row[2].length() > 1024) throw new AssertionError("chunk framing");
            bytes.write(Base64.getDecoder().decode(row[2]));
        }
        String[] end = lines[lines.length - 1].split(" ");
        if (!"RWR_NA_END".equals(end[0]) || Integer.parseInt(end[1]) != lines.length - 2)
            throw new AssertionError("completion missing");
        if (Integer.parseInt(start[1]) != bytes.size()) throw new AssertionError("length mismatch");
        StringBuilder sha = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
            sha.append(String.format("%02x", b & 255));
        if (!sha.toString().equals(start[2]) || !start[2].equals(end[2]))
            throw new AssertionError("digest mismatch");
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        if (input.readInt() != -1 || input.readInt() != text.length()) throw new AssertionError("string lengths");
        for (int i = 0; i < text.length(); i++)
            if (input.readChar() != text.charAt(i)) throw new AssertionError("original code units lost");
        if (input.read() != -1) throw new AssertionError("unexpected data");
        if (DiagnosticReportBlock.firstCategory() != null
                || !text.toString().equals(DiagnosticReportBlock.firstDetail())
                || !DiagnosticReportBlock.emissionComplete()) throw new AssertionError("first observation differs");
        System.out.println("PASS first local diagnostic, long text, null, surrogate and repeat");
    }

    private static void failedOutput(boolean throwsDirectly) throws Exception {
        FatalLatch.Failure prior;
        try {
            GbootBootstrap.requireStateDecoded(false);
            throw new AssertionError("missing prior fatal");
        } catch (FatalLatch.Failure error) { prior = error; }
        Throwable priorCause = prior.getCause();
        PrintStream original = System.err;
        PrintStream failed = throwsDirectly
                ? new PrintStream(new ByteArrayOutputStream()) {
                    @Override public void println(String value) { throw new IllegalStateException("local-sink-failed"); }
                }
                : new PrintStream(new OutputStream() {
                    @Override public void write(int value) throws IOException { throw new IOException("sink-failed"); }
                });
        System.setErr(failed);
        try {
            try {
                DiagnosticReportBlock.reject("uncaughtException", "original local detail");
                throw new AssertionError("report returned");
            } catch (FatalLatch.Failure error) {
                if (error != prior || error.getCause() != priorCause || !"state-decode-failed".equals(error.code()))
                    throw new AssertionError("original fatal replaced");
            }
            if (DiagnosticReportBlock.emissionComplete()) throw new AssertionError("failed output claimed complete");
            if (!"original local detail".equals(DiagnosticReportBlock.firstDetail()))
                throw new AssertionError("in-memory first detail missing");
        } finally { System.setErr(original); }
        System.out.println("PASS failed local output preserves original fatal; durable evidence incomplete");
    }

    private static void concurrent() throws Exception {
        final int count = 16;
        final FatalLatch.Failure[] failures = new FatalLatch.Failure[count];
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        ByteArrayOutputStream local = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(local, true, "UTF-8"));
        Thread[] workers = new Thread[count];
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                workers[i] = new Thread(() -> {
                    try { start.await(); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                    try { DiagnosticReportBlock.reject("category-" + index, "detail-" + index); }
                    catch (FatalLatch.Failure failure) { failures[index] = failure; }
                });
                workers[i].start();
            }
            start.countDown();
            for (Thread worker : workers) {
                worker.join(5000);
                if (worker.isAlive()) throw new AssertionError("diagnostic worker did not terminate");
            }
        } finally { System.setErr(original); }
        if (failures[0] == null) throw new AssertionError("fatal absent");
        for (FatalLatch.Failure failure : failures)
            if (failure != failures[0]) throw new AssertionError("fatal differs between threads");
        String index = DiagnosticReportBlock.firstCategory().substring("category-".length());
        if (!("detail-" + index).equals(DiagnosticReportBlock.firstDetail())) throw new AssertionError("mixed first pair");
        if (local.toString("UTF-8").split("RWR_NA_BEGIN", -1).length != 2)
            throw new AssertionError("multiple first records");
        System.out.println("PASS concurrent atomic first pair and shared fatal");
    }
}
