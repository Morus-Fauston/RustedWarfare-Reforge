package com.rustedwarfare.reforge.reference.boot;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.PrintStream;
import java.security.MessageDigest;
import java.util.Base64;

/** D-082 controlled-copy diagnostic boundary; never a general network guard. */
public final class DiagnosticReportBlock {
    private static boolean recorded;
    private static String firstCategory;
    private static String firstDetail;
    private static boolean emissionComplete;

    private DiagnosticReportBlock() { }

    public static synchronized RuntimeException reject(String category, String detail) {
        try {
            if (!recorded) {
                recorded = true;
                firstCategory = category;
                firstDetail = detail;
                emit(System.err, category, detail);
            }
        } catch (Throwable localFailure) {
            // A failed local emission is not evidence of durable retention, and
            // must never replace the existing fatal or permit remote reporting.
            emissionComplete = false;
        } finally {
            throw GbootBootstrap.unsupportedRouteFailure("diagnostic-remote-report");
        }
    }

    public static synchronized String firstCategory() { return firstCategory; }
    public static synchronized String firstDetail() { return firstDetail; }
    public static synchronized boolean emissionComplete() { return emissionComplete; }

    private static void emit(PrintStream output, String category, String detail) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream framed = new DataOutputStream(buffer);
        writeString(framed, category);
        writeString(framed, detail);
        byte[] bytes = buffer.toByteArray();
        StringBuilder digest = new StringBuilder();
        final char[] hex = "0123456789abcdef".toCharArray();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            digest.append(hex[(b & 255) >>> 4]);
            digest.append(hex[b & 15]);
        }
        String encoded = Base64.getEncoder().encodeToString(bytes);
        output.println("RWR_NA_BEGIN " + bytes.length + " " + digest);
        int count = 0;
        for (int index = 0; index < encoded.length(); index += 1024)
            output.println("RWR_NA_CHUNK " + count++ + " "
                    + encoded.substring(index, Math.min(index + 1024, encoded.length())));
        output.println("RWR_NA_END " + count + " " + digest);
        output.flush();
        emissionComplete = !output.checkError();
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        output.writeInt(value == null ? -1 : value.length());
        if (value != null)
            for (int i = 0; i < value.length(); i++) output.writeChar(value.charAt(i));
    }
}
