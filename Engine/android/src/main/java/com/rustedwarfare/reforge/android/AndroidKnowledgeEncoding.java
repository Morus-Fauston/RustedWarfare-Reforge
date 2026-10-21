package com.rustedwarfare.reforge.android;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Lossless, self-contained Android diagnostic representation. Never changes a gameplay observation. */
public final class AndroidKnowledgeEncoding {
    private AndroidKnowledgeEncoding() {}

    public static Map<String, Object> record(String kind, Map<String, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.put("diagnosticVersion", 2);
        if (!"observation".equals(kind) || !(values.get("sample") instanceof Map<?, ?>)) return result;
        Map<?, ?> sample = (Map<?, ?>) values.get("sample");
        Object knowledge = sample.get("knowledge");
        Object encoded = encode(knowledge);
        if (encoded == knowledge) return result;
        Map<Object, Object> copy = new LinkedHashMap<>(sample);
        copy.put("knowledge", encoded);
        result.put("sample", copy);
        return result;
    }

    /** Unknown shapes are preserved verbatim, including future cell fields. */
    public static Object encode(Object knowledge) {
        if (!(knowledge instanceof List<?>)) return knowledge;
        List<?> cells = (List<?>) knowledge;
        if (cells.size() != 600) return knowledge;
        List<List<Object>> runs = new ArrayList<>();
        List<Object> run = null;
        int originalSize = 2, encodedSize = 45;
        for (Object value : cells) {
            if (!(value instanceof Map<?, ?>)) return knowledge;
            Map<?, ?> cell = (Map<?, ?>) value;
            if (cell.size() != 3 || !cell.containsKey("visible") || !cell.containsKey("gid") || !cell.containsKey("blocked")) return knowledge;
            Object visible = cell.get("visible"), gid = cell.get("gid"), blocked = cell.get("blocked");
            if (!(visible instanceof Boolean) || !(gid == null || gid instanceof Integer || gid instanceof Long)
                || !(blocked == null || blocked instanceof Boolean)
                || ((gid == null || blocked == null) && !(gid == null && blocked == null && visible.equals(false))))
                return knowledge;
            originalSize += 32 + textSize(visible) + textSize(gid) + textSize(blocked);
            if (run != null && Objects.equals(run.get(1), visible) && Objects.equals(run.get(2), gid)
                && Objects.equals(run.get(3), blocked)) run.set(0, (Integer) run.get(0) + 1);
            else {
                run = new ArrayList<>();
                run.add(1); run.add(visible); run.add(gid); run.add(blocked);
                runs.add(run);
            }
        }
        for (List<Object> span : runs)
            encodedSize += 6 + textSize(span.get(0)) + textSize(span.get(1)) + textSize(span.get(2)) + textSize(span.get(3));
        if (encodedSize >= originalSize) return knowledge;
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("encoding", "runs-v1"); encoded.put("count", 600); encoded.put("runs", runs);
        return encoded;
    }

    private static int textSize(Object value) { return value == null ? 4 : value.toString().length(); }
}
