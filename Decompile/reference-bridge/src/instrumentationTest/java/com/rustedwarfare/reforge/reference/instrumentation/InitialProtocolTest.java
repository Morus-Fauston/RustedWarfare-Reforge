package com.rustedwarfare.reforge.reference.instrumentation;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InitialProtocolTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Map<String, String> request = valid();
        InitialProtocol.validate(request);
        checks++;
        reject("unknown", () -> { Map<String, String> r = valid(); r.put("class", "other"); InitialProtocol.validate(r); });
        reject("profile", () -> { Map<String, String> r = valid(); r.put("profile", "other"); InitialProtocol.validate(r); });
        reject("request", () -> { Map<String, String> r = valid(); r.put("requestId", "a\nb"); InitialProtocol.validate(r); });
        reject("uuid", () -> { Map<String, String> r = valid(); r.put("runId", "bad"); InitialProtocol.validate(r); });
        reject("hash", () -> { Map<String, String> r = valid(); r.put("testApkSha256", "0"); InitialProtocol.validate(r); });
        reject("target", () -> { Map<String, String> r = valid(); r.put("targetApkSha256", repeat('0')); InitialProtocol.validate(r); });
        reject("old-target", () -> { Map<String, String> r = valid(); r.put("targetApkSha256", "842564ef9d353844dd67ab93a3f99ce328d92fe00f2c102fe5dab4266ead3c5d"); InitialProtocol.validate(r); });
        reject("signer", () -> { Map<String, String> r = valid(); r.put("signerSha256", repeat('0')); InitialProtocol.validate(r); });
        InitialProtocol.Stream stream = new InitialProtocol.Stream();
        reject("stage", () -> stream.append("bootstrap", new LinkedHashMap<String, Object>()));
        String first = stream.append("identity", map("raw", "a\n\"b\\c"));
        check(first.equals("{\"raw\":\"a\\n\\\"b\\\\c\"}"), "json escaping");
        check(stream.hashes().size() == 1 && stream.hashes().get(0).equals(InitialProtocol.sha256(first.getBytes("UTF-8"))), "actual byte hash");
        reject("repeat", () -> stream.append("identity", map("x", 1)));
        reject("nonfinite", () -> InitialProtocol.json(map("x", Float.NaN)));
        reject("object", () -> InitialProtocol.json(map("x", new Object())));
        reject("limit", () -> stream.append("launcher-ready", map("x", new String(new char[17000]))));
        check(stream.hashes().size() == 1, "rejection does not consume sequence");
        stream.append("failure", map("error", "first"));
        stream.append("final", map("status", "FAILED"));
        reject("afterfinal", () -> stream.append("cleanup", map("x", 1)));
        check(InitialProtocol.json(map("units", Arrays.asList(map("ej", "9223372036854775807"), null))).contains("\"9223372036854775807\""), "native long stays string");
        InitialProtocol.requireSnapshot(false, true, true, 0, 0, true, true, false, true);
        checks++;
        reject("failed-map", () -> InitialProtocol.requireSnapshot(false, true, false, 0, 0, true, true, false, true));
        reject("loading", () -> InitialProtocol.requireSnapshot(true, true, true, 0, 0, true, true, false, true));
        reject("extra-pre", () -> InitialProtocol.requireSnapshot(false, true, true, 1, 0, true, true, false, true));
        reject("different-instance", () -> InitialProtocol.requireSnapshot(false, true, true, 0, 1, false, true, false, true));
        reject("unregistered", () -> InitialProtocol.requireSnapshot(false, true, true, 0, 1, true, true, false, false));
        System.out.println("PASS InitialProtocol checks=" + checks);
    }

    private static Map<String, String> valid() {
        Map<String, String> r = new LinkedHashMap<String, String>();
        r.put("schemaVersion", "1"); r.put("profile", "r12.p001.single-unit.trial.v1");
        r.put("requestId", "r12-one"); r.put("runId", "00000000-0000-4000-8000-000000000001");
        r.put("targetApkSha256", "ad92fe1dbf2fff203a5867590b3e8ea96dffa09f6fe372ff93893a5420904c6b");
        r.put("testApkSha256", repeat('1'));
        r.put("signerSha256", "efcd2943ba1d0793b8283a09bab852311f52ab6771029c209f612cd1d990513d");
        return r;
    }

    private static String repeat(char c) { char[] a = new char[64]; Arrays.fill(a, c); return new String(a); }
    private static Map<String, Object> map(String key, Object value) { Map<String, Object> m = new LinkedHashMap<String, Object>(); m.put(key, value); return m; }
    private interface Action { void run() throws Exception; }
    private static void reject(String name, Action action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("accepted " + name);
    }
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); checks++; }
}
