package com.rustedwarfare.reforge.android.metadata;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory JSON only; no Android dependency and no filesystem output. */
final class MetadataJson {
    private MetadataJson() { }

    static String encode(MetadataProbe.Result result) {
        Map<String, Object> root = object();
        root.put("schema", "e9-storage-metadata-v1");
        root.put("complete", result.completed);
        root.put("success", result.successful());
        root.put("identityObservationStarted", result.identityObservationStarted);
        root.put("identityObservationCompleted", result.identityObservationCompleted);
        root.put("metadataProbeRunnerExecuted", true);
        root.put("metadataProbeTargetProcessExecuted", result.metadataProbeTargetProcessExecuted);
        root.put("applicationExecutedForProduct", false);
        root.put("runtimeVerified", false);
        root.put("externalRootVerified", false);
        root.put("oldRunsProtected", false);
        root.put("privateTreeVerified", false);
        root.put("installAllowed", false);
        root.put("selinuxDomain", null);
        root.put("mountNamespace", null);
        root.put("binding", binding(result.expected));
        Map<String, Object> clock = object();
        clock.put("startUtcMillis", result.startUtcMillis);
        clock.put("endUtcMillis", result.endUtcMillis);
        clock.put("startUtc", Instant.ofEpochMilli(result.startUtcMillis).toString());
        clock.put("endUtc", Instant.ofEpochMilli(result.endUtcMillis).toString());
        clock.put("startMonotonicNanos", result.startMonotonicNanos);
        clock.put("endMonotonicNanos", result.endMonotonicNanos);
        root.put("clock", clock);
        Map<String, Object> identity = object();
        identity.put("processUid", result.identity.processUid);
        identity.put("processPid", result.identity.processPid);
        identity.put("processName", result.identity.processName);
        identity.put("processUser0", result.identity.processUser0);
        identity.put("sdkInt", result.identity.sdkInt);
        identity.put("main", packageIdentity(result.identity.main));
        identity.put("test", packageIdentity(result.identity.test));
        root.put("identity", identity);
        List<Object> observations = new ArrayList<Object>();
        for (String path : result.paths) {
            Map<String, Object> observation = object();
            observation.put("path", path);
            MetadataProbe.Stat stat = result.stats.get(path);
            Map<String, Object> attrs = null;
            if (stat != null) {
                attrs = object();
                attrs.put("mode", stat.mode); attrs.put("uid", stat.uid); attrs.put("gid", stat.gid);
                attrs.put("device", stat.device); attrs.put("inode", stat.inode); attrs.put("size", stat.size);
                attrs.put("modifiedSeconds", stat.modifiedSeconds); attrs.put("changedSeconds", stat.changedSeconds);
            }
            observation.put("stat", attrs);
            observation.put("readlinkObserved", result.links.containsKey(path));
            observation.put("readlinkText", result.links.get(path));
            observations.add(observation);
        }
        root.put("observations", observations);
        Map<String, Object> primary = null;
        if (result.primaryFailure != null) {
            primary = error(result.primaryFailure);
            primary.put("stage", result.primaryStage);
            primary.put("path", result.primaryPath);
            primary.put("errno", result.primaryErrno);
        }
        root.put("primaryFailure", primary);
        List<Object> transport = new ArrayList<Object>();
        for (MetadataProbe.DeliveryFailure failure : result.transportFailures) {
            Map<String, Object> item = error(failure.failure);
            item.put("phase", failure.phase);
            transport.add(item);
        }
        root.put("transportFailures", transport);
        List<Object> secondary = new ArrayList<Object>();
        for (Throwable failure : result.secondaryFailures) secondary.add(error(failure));
        root.put("secondaryFailures", secondary);
        StringBuilder out = new StringBuilder();
        append(out, root);
        return out.toString();
    }

    private static Map<String, Object> binding(MetadataProbe.Bindings expected) {
        if (expected == null) return null;
        Map<String, Object> result = object();
        result.put("bindingId", expected.bindingId);
        result.put("mainApkSha256", expected.mainApkSha256); result.put("mainApkBytes", expected.mainApkBytes);
        result.put("testApkSha256", expected.testApkSha256); result.put("testApkBytes", expected.testApkBytes);
        result.put("certificateSha256", expected.certificateSha256); result.put("certificateBytes", expected.certificateBytes);
        return result;
    }

    private static Map<String, Object> packageIdentity(MetadataProbe.PackageIdentity actual) {
        Map<String, Object> result = object();
        result.put("contextPackage", actual.contextPackage); result.put("contextUid", actual.contextUid);
        result.put("packageName", actual.packageName); result.put("uid", actual.uid);
        result.put("processName", actual.processName);
        result.put("sourceDir", actual.sourceDir); result.put("publicSourceDir", actual.publicSourceDir);
        result.put("splitCount", actual.splitCount); result.put("signerCount", actual.signerCount);
        result.put("multipleSigners", actual.multipleSigners);
        result.put("apkSha256", actual.apkSha256); result.put("apkBytes", actual.apkBytes);
        result.put("certificateSha256", actual.certificateSha256); result.put("certificateBytes", actual.certificateBytes);
        result.put("certificateDerHex", actual.certificateDerHex);
        return result;
    }

    private static Map<String, Object> error(Throwable failure) {
        Map<String, Object> error = object();
        error.put("exceptionType", failure.getClass().getName());
        error.put("message", failure.getMessage());
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        error.put("stackTrace", text.toString());
        return error;
    }

    private static Map<String, Object> object() { return new LinkedHashMap<String, Object>(); }

    private static void append(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String) string(out, (String) value);
        else if (value instanceof Boolean || value instanceof Number) out.append(value);
        else if (value instanceof Map) {
            out.append('{'); boolean first = true;
            for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(','); first = false;
                string(out, (String) item.getKey()); out.append(':'); append(out, item.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('['); boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) out.append(','); first = false; append(out, item);
            }
            out.append(']');
        } else throw new IllegalArgumentException("Unsupported JSON value");
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '"' || value == '\\') out.append('\\').append(value);
            else if (value == '\n') out.append("\\n");
            else if (value == '\r') out.append("\\r");
            else if (value == '\t') out.append("\\t");
            else if (value < 0x20 || Character.isSurrogate(value)) {
                out.append("\\u");
                String hex = Integer.toHexString(value);
                for (int padding = hex.length(); padding < 4; padding++) out.append('0');
                out.append(hex);
            } else out.append(value);
        }
        out.append('"');
    }
}
