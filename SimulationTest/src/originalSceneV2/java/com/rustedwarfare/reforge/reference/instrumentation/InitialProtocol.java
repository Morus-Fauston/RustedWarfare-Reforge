package com.rustedwarfare.reforge.reference.instrumentation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded transport rules; host tests do not emulate the original game. */
public final class InitialProtocol {
    public static final String PROFILE = "r12.p001.single-unit.trial.v1";
    public static final String TARGET_HASH = "ad92fe1dbf2fff203a5867590b3e8ea96dffa09f6fe372ff93893a5420904c6b";
    public static final String MOTION_TARGET_HASH = "f092017e2d33a0df305224646c59d2c3a7e156f5cd1df7578d187804d0de3356";
    public static final String SIGNER_HASH = "efcd2943ba1d0793b8283a09bab852311f52ab6771029c209f612cd1d990513d";
    private static final Set<String> KEYS = new HashSet<String>(Arrays.asList(
            "schemaVersion", "profile", "requestId", "runId", "targetApkSha256", "testApkSha256", "signerSha256"));

    private InitialProtocol() { }

    public static void validate(Map<String, String> request) {
        require(request != null && request.keySet().equals(KEYS), "request-keys");
        for (String value : request.values()) require(value != null && !value.isEmpty(), "request-empty");
        require("1".equals(request.get("schemaVersion")), "schema-version");
        require(PROFILE.equals(request.get("profile")) || motionMode(request)!=null, "profile");
        require(request.get("requestId").matches("[A-Za-z0-9_-]{1,64}"), "request-id");
        String run = request.get("runId");
        require(run.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                && UUID.fromString(run).toString().equals(run), "run-id");
        require(expectedTargetHash(request).equals(request.get("targetApkSha256")), "target-pin");
        require(SIGNER_HASH.equals(request.get("signerSha256")), "signer-pin");
        require(request.get("testApkSha256").matches("[0-9a-f]{64}"), "test-pin");
    }
    public static String motionMode(Map<String,String> request) {
        String profile=request.get("profile");
        for(String mode:new String[]{"on","off","bare","fault"})
            if(("r21.p001.normal-motion."+mode+".v1").equals(profile))return mode;
        return null;
    }
    public static String expectedTargetHash(Map<String,String> request) {
        String mode=motionMode(request);return mode==null || mode.equals("bare")?TARGET_HASH:MOTION_TARGET_HASH;
    }

    public static void requireSnapshot(boolean loading, boolean ready, boolean mapReady,
            int preSize, int postSize, boolean sameInstance, boolean registered, boolean destroyed, boolean counted) {
        require(!loading && ready && mapReady, "native-load-state");
        require(preSize == 0 && (postSize == 0 || postSize == 1), "native-table-count");
        if (postSize == 1) require(sameInstance && registered && !destroyed && counted, "native-registration");
    }

    public static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }

    public static String sha256(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) { out[i * 2] = alphabet[(bytes[i] >>> 4) & 15]; out[i * 2 + 1] = alphabet[bytes[i] & 15]; }
        return new String(out);
    }

    public static String json(Object value) {
        StringBuilder out = new StringBuilder();
        encode(value, out, 0);
        return out.toString();
    }

    private static void encode(Object value, StringBuilder out, int depth) {
        require(depth <= 24, "json-depth");
        if (value == null) { out.append("null"); return; }
        if (value instanceof String) {
            out.append('"');
            String text = (String) value;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"' || c == '\\') { out.append('\\').append(c); }
                else if (c == '\n') out.append("\\n");
                else if (c == '\r') out.append("\\r");
                else if (c == '\t') out.append("\\t");
                else if (c < 32 || Character.isSurrogate(c)) {
                    String h = Integer.toHexString(c); out.append("\\u");
                    for (int j = h.length(); j < 4; j++) out.append('0'); out.append(h);
                } else out.append(c);
            }
            out.append('"'); return;
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) { out.append(value); return; }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            require(!Double.isNaN(number) && !Double.isInfinite(number), "json-nonfinite"); out.append(value); return;
        }
        if (value instanceof Map) {
            out.append('{'); boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                require(entry.getKey() instanceof String, "json-key");
                if (!first) out.append(','); first = false;
                encode(entry.getKey(), out, depth + 1); out.append(':'); encode(entry.getValue(), out, depth + 1);
            }
            out.append('}'); return;
        }
        if (value instanceof List) {
            out.append('['); boolean first = true;
            for (Object item : (List<?>) value) { if (!first) out.append(','); first = false; encode(item, out, depth + 1); }
            out.append(']'); return;
        }
        throw new IllegalArgumentException("json-type:" + value.getClass().getName());
    }

    public static final class Stream {
        private static final List<String> ORDER = Arrays.asList("identity", "launcher-ready", "bootstrap", "native-pre-spawn", "native-initial", "cleanup", "final");
        private final List<String> hashes = new ArrayList<String>();
        private int next;
        private int bytes;
        private boolean failed;
        private boolean closed;

        public List<String> hashes() { return new ArrayList<String>(hashes); }

        public String append(String kind, Map<String, Object> record) {
            require(!closed, "stream-closed");
            boolean failure = "failure".equals(kind);
            require((failure && !failed) || (failed && "final".equals(kind))
                    || (!failed && next < ORDER.size() && ORDER.get(next).equals(kind)), "stage-order");
            String json = json(record);
            byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
            require(encoded.length <= 16384 && bytes + encoded.length <= 131072 && hashes.size() < 32, "stream-limit");
            hashes.add(sha256(encoded)); bytes += encoded.length;
            if (failure) failed = true; else next++;
            if ("final".equals(kind)) closed = true;
            return json;
        }
    }
}
