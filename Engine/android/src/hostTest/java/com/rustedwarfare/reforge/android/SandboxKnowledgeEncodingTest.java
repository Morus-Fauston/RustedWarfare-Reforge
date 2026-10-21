package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;
import com.rustedwarfare.reforge.client.ClientEvidence;
import com.rustedwarfare.reforge.client.ControlledScenario;
import com.rustedwarfare.reforge.client.SandboxMode;
import com.rustedwarfare.reforge.client.SandboxOptions;
import com.rustedwarfare.reforge.client.SandboxSession;
import com.rustedwarfare.reforge.core.sim.Position;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure JVM codec checks and an explicit capacity fixture; not ART/UI/performance evidence. */
public final class SandboxKnowledgeEncodingTest {
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void rejected(String text) {
        try { new SandboxKnowledgeView(new JsonReader().parse(text)); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Malformed encoded knowledge accepted: " + text);
    }
    private static void versionIngress() {
        Map<String, Object> encoded = AndroidKnowledgeEncoding.record("observation", Map.of(
            "sample", Map.of("knowledge", cells(1)), "phase", "step"));
        encoded.put("kind", "observation");
        // JsonWriter.value normalizes integral Doubles to integer text. Keep negative wire literals explicit.
        require(json(Map.of("v", 2.0)).equals("{\"v\":2}"), "Fixture serializer normalization changed");
        for (Object marker : new Object[]{null, "2", 3, 2.0, true}) {
            Map<String, Object> invalid = new LinkedHashMap<>(encoded);
            if (marker == null) invalid.remove("diagnosticVersion"); else invalid.put("diagnosticVersion", marker);
            String wire = json(invalid);
            if (marker instanceof Double) wire = wire.replace("\"diagnosticVersion\":2", "\"diagnosticVersion\":2.0");
            try {
                new SandboxFunctionalOracle.Stream().feed((wire + "\n").getBytes(StandardCharsets.UTF_8), 0, false);
            } catch (IllegalArgumentException expected) { continue; }
            throw new AssertionError("Encoded ingress accepted missing/invalid diagnosticVersion=" + marker);
        }
        var valid = new SandboxFunctionalOracle.Stream();
        valid.feed((json(encoded) + "\n").getBytes(StandardCharsets.UTF_8), 0, false); valid.complete();
        require(valid.records.size() == 1, "Versioned encoded row lost");
        Map<String, Object> late = new LinkedHashMap<>(encoded); late.put("diagnosticVersion", 3);
        try {
            new SandboxFunctionalOracle.Stream().feed((json(encoded) + "\n" + json(late) + "\n" + json(encoded) + "\n")
                .getBytes(StandardCharsets.UTF_8), 0, false);
            throw new AssertionError("Same-batch late version error was hidden by a valid row");
        } catch (IllegalArgumentException expected) { /* Whole-batch ingress must fail. */ }
        var old = new SandboxFunctionalOracle.Stream();
        old.feed((json(Map.of("kind", "observation", "sample", Map.of("knowledge", cells(1)))) + "\n")
            .getBytes(StandardCharsets.UTF_8), 0, false); old.complete();
        System.out.println("PASS encoded row ingress requires integer diagnosticVersion=2; legacy arrays remain valid");
    }
    private static Map<String, Object> cell(boolean visible, Object gid, Object blocked) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("visible", visible); value.put("gid", gid); value.put("blocked", blocked); return value;
    }
    private static List<Map<String, Object>> cells(int pattern) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            if (pattern == 0) values.add(cell(false, null, null));
            else if (pattern == 1) values.add(cell(true, 1, false)); // Actual Kotlin Int / Java Integer.
            else if (pattern == 2) values.add(cell(i % 2 == 0, i % 2 == 0 ? Long.MIN_VALUE : Long.MAX_VALUE, i % 3 == 0));
            else values.add(i % 3 == 0 ? cell(false, null, null) : cell(i % 5 == 0, (long) (i / 23), i % 7 == 0));
        }
        return values;
    }
    private static void write(JsonWriter writer, Object value) throws IOException {
        if (value instanceof Map<?, ?>) {
            writer.object();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                writer.name((String) entry.getKey()); write(writer, entry.getValue());
            }
            writer.pop();
        } else if (value instanceof List<?>) {
            writer.array(); for (Object item : (List<?>) value) write(writer, item); writer.pop();
        } else writer.value(value);
    }
    static String json(Object value) {
        try {
            StringWriter text = new StringWriter();
            JsonWriter writer = new JsonWriter(text); writer.setOutputType(JsonWriter.OutputType.json);
            write(writer, value); writer.close(); return text.toString();
        } catch (IOException failure) { throw new AssertionError(failure); }
    }
    private static void same(List<?> original, Object encoded) {
        JsonValue raw = new JsonReader().parse(json(encoded)); String before = raw.toJson(JsonWriter.OutputType.json);
        SandboxKnowledgeView view = new SandboxKnowledgeView(raw);
        int count = 0;
        for (JsonValue actual : view) {
            Map<?, ?> expected = (Map<?, ?>) original.get(count++);
            require(actual.getBoolean("visible") == (Boolean) expected.get("visible"), "Visibility changed");
            Object gid = expected.get("gid"), blocked = expected.get("blocked");
            require(actual.get("gid").isNull() == (gid == null) && actual.get("blocked").isNull() == (blocked == null), "Null knowledge changed");
            if (gid != null) require(actual.getLong("gid") == ((Number) gid).longValue() && actual.getBoolean("blocked") == (Boolean) blocked, "Typed knowledge changed");
        }
        require(count == 600, "Lost a cell");
        require(view.get(599).toJson(JsonWriter.OutputType.json).equals(new SandboxKnowledgeView(raw).get(599).toJson(JsonWriter.OutputType.json)), "Indexed lookup differs");
        require(raw.toJson(JsonWriter.OutputType.json).equals(before), "Decoder mutated raw records");
    }
    static JsonValue encodedSample(JsonValue sample) {
        JsonValue copy = new JsonReader().parse(sample.toJson(JsonWriter.OutputType.json));
        List<Map<String, Object>> raw = new ArrayList<>();
        for (JsonValue value : SandboxKnowledgeView.sample(sample))
            raw.add(cell(value.getBoolean("visible"), value.get("gid").isNull() ? null : value.getLong("gid"),
                value.get("blocked").isNull() ? null : value.getBoolean("blocked")));
        copy.remove("knowledge"); copy.addChild("knowledge", new JsonReader().parse(json(AndroidKnowledgeEncoding.encode(raw))));
        return copy;
    }
    private static JsonValue retentionRow(String mode, boolean initial, int step) {
        List<Map<String, Object>> raw = cells(0);
        raw.set(155, initial ? cell(true, 1, false) : mode.equals("MEMORY") ? cell(false, 1, false) : cell(false, null, null));
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("worldId", "w"); sample.put("playerId", "p1"); sample.put("scenarioId", "p002-knowledge");
        sample.put("stepIndex", initial ? 0 : step); sample.put("simulationTime", initial ? 0 : step + 1);
        sample.put("units", List.of(Map.of("unitId", "u1", "position", Map.of("x", 110, "y", initial ? 110 : 120))));
        sample.put("knowledge", raw);
        Map<String, Object> row = AndroidKnowledgeEncoding.record("observation", Map.of("sample", sample,
            "phase", initial ? "initial" : "step", "generation", 1, "sequence", initial ? 10 : 11 + step));
        row.put("kind", "observation"); return new JsonReader().parse(json(row));
    }
    private static void oracleWiring() {
        for (String mode : new String[]{"MEMORY", "HARDCORE"}) {
            var retention = new SandboxFunctionalOracle.Retention(retentionRow(mode, true, 0), mode);
            for (int step = 0; step < 3; step++) retention.accept(retentionRow(mode, false, step));
            require(retention.ready(), "Encoded retention did not pass");
            JsonValue late = retentionRow(mode, false, 3);
            // A validly encoded late reappearance resets the requirement; previous green is not permanent.
            JsonValue visible = retentionRow(mode, true, 0).get("sample").get("knowledge");
            late.get("sample").remove("knowledge"); late.get("sample").addChild("knowledge", visible);
            retention.accept(late); require(!retention.ready(), "Encoded late visibility was ignored");
        }
        SandboxRecreateOracleTest.encodedKnowledgeRegression();
        System.out.println("PASS encoded retention and old/new/mixed recreation knowledge paths");
    }
    private static void unit() {
        versionIngress();
        for (int pattern = 0; pattern < 4; pattern++) {
            List<Map<String, Object>> original = cells(pattern); String before = json(original);
            Object encoded = AndroidKnowledgeEncoding.encode(original);
            require(encoded instanceof Map<?, ?>, "Actual Integer/Long cells did not encode");
            require(json(encoded).length() < before.length(), "Selected representation is not smaller");
            same(original, encoded); same(original, original);
            require(json(original).equals(before), "Encoder mutated input");
        }
        List<Map<String, Object>> future = cells(1); future.get(155).put("future", "must survive");
        require(AndroidKnowledgeEncoding.encode(future) == future, "New field was silently dropped");
        for (Object unsupported : new Object[]{1.0, 1.5f, "1", true}) {
            List<Map<String, Object>> invalid = cells(1); invalid.get(300).put("gid", unsupported);
            require(AndroidKnowledgeEncoding.encode(invalid) == invalid, "Noninteger gid coerced");
        }
        require(AndroidKnowledgeEncoding.encode(null) == null, "NO_FOG null changed");
        List<Map<String, Object>> shortMap = cells(0); shortMap.remove(0);
        require(AndroidKnowledgeEncoding.encode(shortMap) == shortMap, "Unknown map shape truncated");
        Map<String, Object> sample = new LinkedHashMap<>(); sample.put("knowledge", cells(1)); sample.put("untouched", List.of("x", 1L));
        Map<String, Object> values = Map.of("sample", sample, "phase", "step", "generation", 4L);
        String before = json(values);
        Map<String, Object> record = AndroidKnowledgeEncoding.record("observation", values);
        require(record.get("diagnosticVersion").equals(2) && record.get("sample") != sample, "Writer seam did not copy/version sample");
        require(((Map<?, ?>) record.get("sample")).get("knowledge") instanceof Map<?, ?> && record.get("generation").equals(4L), "Writer seam did not encode actual map");
        require(json(values).equals(before), "Writer seam mutated caller map");
        require(AndroidKnowledgeEncoding.record("other", values).get("sample") == sample, "Encoded non-observation");
        String valid = "{encoding:runs-v1,count:600,runs:[[600,false,null,null]]}";
        new SandboxKnowledgeView(new JsonReader().parse(valid));
        for (String bad : new String[]{valid.replace("runs-v1", "runs-v2"), valid.replace("count:600", "count:599"),
                valid.replace("count:600", "count:600.0"), valid.replace("600,false", "0,false"),
                valid.replace("600,false", "-1,false"), valid.replace("600,false", "601,false"),
                valid.replace("600,false", "599,false"), valid.replace("600,false", "9223372036854775807,false"),
                valid.replace("600,false", "600.0,false"), valid.replace("false,null", "\"false\",null"),
                valid.replace("false,null,null", "true,null,null"), valid.replace("false,null,null", "false,1.0,false"),
                valid.replace("false,null,null", "false,null,false"), valid.replace("false,null,null", "false,1,null"),
                valid.replace("false,null,null", "false,\"1\",false"), valid.replace("false,null,null", "false,1,\"false\""),
                valid.replace("null,null", "null"), valid.replace("null,null", "null,null,null"),
                valid.replace("[[600,false,null,null]]", "[]"), valid.replace("[[600,false,null,null]]", "[[600,false,null,null],[1,false,null,null]]"),
                valid.replace("count:600", "extra:1,count:600")}) rejected(bad);
        oracleWiring();
        System.out.println("PASS reversible 600-cell Integer/Long/null views, immutable writer seam, fallback and strict malformed controls");
    }

    private static void capacity(Path output) throws Exception {
        Path root = Path.of("TestLab/local/P002-知识沙盒输入").toAbsolutePath();
        for (SandboxMode mode : new SandboxMode[]{SandboxMode.MEMORY, SandboxMode.HARDCORE}) {
            Path leaf = output.resolve(mode.name() + "-3000-host.jsonl");
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            long[] counts = new long[6]; // records, bytes, maxLine, cells, step records, actual Integer gids.
            try (var stream = Files.newOutputStream(leaf, StandardOpenOption.CREATE_NEW)) {
                ClientEvidence evidence = (kind, values) -> {
                    try {
                        String before = json(values);
                        Map<String, Object> encoded = AndroidKnowledgeEncoding.record(kind, values);
                        if (kind.equals("observation")) {
                            Map<?, ?> sample = (Map<?, ?>) values.get("sample");
                            List<?> knowledge = (List<?>) sample.get("knowledge");
                            require(knowledge.size() == 600, "Real scenario knowledge shape changed");
                            for (Object rawCell : knowledge) if (((Map<?, ?>) rawCell).get("gid") instanceof Integer) counts[5]++;
                            Object compressed = ((Map<?, ?>) encoded.get("sample")).get("knowledge");
                            require(compressed instanceof Map<?, ?>, "Real cell map fell back instead of encoding");
                            same(knowledge, compressed); counts[3] += 600;
                            if (values.get("phase").equals("step")) counts[4]++;
                        }
                        require(json(values).equals(before), "Real input was mutated");
                        encoded.put("kind", kind); encoded.put("sequence", counts[0]++);
                        encoded.put("timeMillis", System.currentTimeMillis());
                        byte[] line = (json(encoded) + "\n").getBytes(StandardCharsets.UTF_8);
                        stream.write(line); stream.flush(); hash.update(line);
                        counts[1] += line.length; counts[2] = Math.max(counts[2], line.length);
                    } catch (IOException failure) { throw new IllegalStateException(failure); }
                };
                try (SandboxSession session = new SandboxSession(ControlledScenario.INSTANCE.load(root), evidence, new SandboxOptions(mode, false))) {
                    session.select(new Position(110, 110)); session.move(new Position(350, 110));
                    // Capacity fixture: 2500 generated ticks + a worst-sized 500-tick closing allowance.
                    // This does not apply the live entry's terminal boundary or count later ticks as eligibility.
                    for (int step = 0; step < 3000; step++) session.tick();
                }
            }
            require(counts[4] == 3000 && counts[5] > 0, "Capacity fixture omitted ticks/real Integer input");
            require(counts[1] < 12L * 1024 * 1024, "Complete host trace exceeds business byte cap");
            require(Files.size(leaf) == counts[1], "Actual file byte count differs");
            System.out.println(mode + " records=" + counts[0] + " bytes=" + counts[1] + " maxLine=" + counts[2]
                + " cellsCompared=" + counts[3] + " steps=" + counts[4] + " IntegerGids=" + counts[5]
                + " sha256=" + java.util.HexFormat.of().formatHex(hash.digest()));
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("unit")) unit();
        else if (args.length == 2 && args[0].equals("capacity")) capacity(Path.of(args[1]));
        else throw new IllegalArgumentException("Expected unit or capacity <new-output-directory>");
    }
}
