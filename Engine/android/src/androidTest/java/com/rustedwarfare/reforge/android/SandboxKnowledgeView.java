package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonValue;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Read-only lazy view. Raw records keep the device's encoded bytes/values. */
public final class SandboxKnowledgeView implements Iterable<JsonValue> {
    private final JsonValue[] values;
    private final int[] ends;
    private final boolean encoded;
    private int cached = -1;
    private JsonValue cachedCell;

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalArgumentException(message);
    }
    private static void cell(JsonValue visible, JsonValue gid, JsonValue blocked) {
        require(visible != null && visible.isBoolean() && gid != null && blocked != null
            && ((gid.isLong() && blocked.isBoolean()) || (gid.isNull() && blocked.isNull() && !visible.asBoolean())),
            "Malformed knowledge cell");
    }

    public SandboxKnowledgeView(JsonValue knowledge) {
        require(knowledge != null, "Missing knowledge");
        encoded = knowledge.isObject();
        ArrayList<JsonValue> source = new ArrayList<>();
        ArrayList<Integer> boundaries = new ArrayList<>();
        int count = 0;
        if (encoded) {
            JsonValue version = knowledge.get("encoding"), size = knowledge.get("count"), runs = knowledge.get("runs");
            require(knowledge.size == 3 && version != null && version.isString() && version.asString().equals("runs-v1")
                && size != null && size.isLong() && size.asLong() == 600 && runs != null && runs.isArray(),
                "Unknown knowledge encoding/shape");
            for (JsonValue span : runs) {
                require(span.isArray() && span.size == 4 && span.get(0).isLong(), "Malformed knowledge span");
                long length = span.getLong(0);
                require(length > 0 && length <= 600 - count, "Knowledge span overflow/length");
                cell(span.get(1), span.get(2), span.get(3));
                count += (int) length; source.add(span); boundaries.add(count);
            }
        } else {
            require(knowledge.isArray() && knowledge.size == 600, "Expected all 600 knowledge cells");
            for (JsonValue value : knowledge) {
                require(value.isObject(), "Knowledge cell must be an object");
                cell(value.get("visible"), value.get("gid"), value.get("blocked"));
                source.add(value); boundaries.add(++count);
            }
        }
        require(count == 600, "Knowledge spans do not fill 600 cells");
        values = source.toArray(new JsonValue[0]);
        ends = new int[boundaries.size()];
        for (int i = 0; i < ends.length; i++) ends[i] = boundaries.get(i);
    }

    public static SandboxKnowledgeView sample(JsonValue sample) {
        require(sample != null && sample.isObject(), "Missing knowledge sample");
        return new SandboxKnowledgeView(sample.get("knowledge"));
    }
    /** Row context belongs at ingestion, not at sample-only callers. Legacy arrays need no version. */
    public static void validateRecord(JsonValue row) {
        if (!row.getString("kind", "").equals("observation")) return;
        JsonValue sample = row.get("sample");
        JsonValue knowledge = sample != null && sample.isObject() ? sample.get("knowledge") : null;
        if (knowledge == null || !knowledge.isObject()) return;
        JsonValue version = row.get("diagnosticVersion");
        require(version != null && version.isLong() && version.asLong() == 2, "Encoded row requires integer diagnosticVersion=2");
        new SandboxKnowledgeView(knowledge);
    }
    public JsonValue get(int index) {
        require(index >= 0 && index < 600, "Knowledge index outside map");
        int low = 0, high = ends.length - 1;
        while (low < high) { int middle = (low + high) >>> 1; if (index < ends[middle]) high = middle; else low = middle + 1; }
        if (!encoded) return values[low];
        if (cached != low) {
            JsonValue span = values[low], value = new JsonValue(JsonValue.ValueType.object);
            value.addChild("visible", new JsonValue(span.getBoolean(1)));
            value.addChild("gid", span.get(2).isNull() ? new JsonValue(JsonValue.ValueType.nullValue) : new JsonValue(span.getLong(2)));
            value.addChild("blocked", span.get(3).isNull() ? new JsonValue(JsonValue.ValueType.nullValue) : new JsonValue(span.getBoolean(3)));
            cachedCell = value; cached = low;
        }
        return cachedCell;
    }
    @Override public Iterator<JsonValue> iterator() {
        return new Iterator<JsonValue>() {
            private int at;
            public boolean hasNext() { return at < 600; }
            public JsonValue next() { if (!hasNext()) throw new NoSuchElementException(); return get(at++); }
        };
    }
}
