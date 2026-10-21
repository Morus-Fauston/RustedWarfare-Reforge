package com.rustedwarfare.reforge.android;

import java.util.Map;

/** TEST-only finite contract checks, independent of Activity and simulation internals. */
public final class SandboxFunctionalOracle {
    public static final class Stream {
        public final java.util.List<com.badlogic.gdx.utils.JsonValue> records = new java.util.ArrayList<>();
        public long bytes;
        public int steps;
        private final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
        private int errorCursor;
        private boolean expectedLoadArmed, expectedLoadRequested, expectedLoadSeen;
        public final boolean longTrace;
        private int businessEndSteps = -1;
        private long terminalSequence = -1;
        private IllegalArgumentException firstFault;
        public Stream() { this(false); }
        private Stream(boolean longTrace) { this.longTrace = longTrace; }
        /** Capture reserve is separate from qualification and permission to send another input. */
        public static Stream forLongTrace() { return new Stream(true); }
        private IllegalArgumentException fault(IllegalArgumentException failure, int atSteps) {
            if (longTrace) {
                if (firstFault == null) firstFault = failure;
                else if (firstFault != failure) firstFault.addSuppressed(failure);
                endAtCount(Math.min(atSteps, 2500));
                return firstFault;
            }
            return failure;
        }
        private void endAtCount(int atSteps) {
            if (businessEndSteps < 0 || atSteps < businessEndSteps) businessEndSteps = atSteps;
        }
        private void tailBudget() {
            if (longTrace && (steps > 3000 || (businessEndSteps >= 0 && steps - businessEndSteps > 500)))
                throw fault(new IllegalArgumentException("Long capture total/tail step budget exceeded"), steps);
        }
        public void failBusiness(Throwable failure) {
            if (longTrace) fault(failure instanceof IllegalArgumentException ? (IllegalArgumentException) failure
                : new IllegalArgumentException("Long business failed", failure), steps);
        }
        public void failBusinessAt(Throwable failure, com.badlogic.gdx.utils.JsonValue row) {
            if (!longTrace) return;
            int count = 0; boolean found = false;
            for (com.badlogic.gdx.utils.JsonValue actual : records) {
                if (actual.getString("kind").equals("observation") && actual.getString("phase", "").equals("step")) count++;
                if (actual == row) { found = true; break; }
            }
            require(found, "Business failure row must be actually captured");
            fault(failure instanceof IllegalArgumentException ? (IllegalArgumentException) failure
                : new IllegalArgumentException("Long business failed", failure), count);
            tailBudget();
        }
        public int businessEndSteps() { return businessEndSteps; }
        public long terminalSequence() { return terminalSequence; }
        public void expectLoadFailure(long generation) {
            require(!longTrace && generation==1 && !expectedLoadArmed && records.stream().noneMatch(r->r.getString("kind").equals("load-request")),
                "Expected failure must be armed once at fresh setup");
            expectedLoadArmed=true;
        }
        public boolean expectedLoadFailureObserved(){return expectedLoadSeen;}
        public void checkInput(long elapsedMillis) {
            feed(new byte[0], elapsedMillis, false);
            if (!longTrace) return;
            if (firstFault != null) throw firstFault;
            if (businessEndSteps >= 0 || steps >= 2500)
                throw fault(new IllegalArgumentException("No more input after long business boundary"), steps);
        }
        public void checkAwait(boolean satisfied, long elapsedMillis) {
            // This guard runs AFTER evaluating an already captured predicate as well as before polling.
            feed(new byte[0], elapsedMillis, false);
            if (!longTrace) return;
            if (firstFault != null) throw firstFault;
            if (!satisfied && (businessEndSteps >= 0 || steps >= 2500))
                throw fault(new IllegalArgumentException("Long business exhausted before its required result"), steps);
        }
        public void endBusinessAt(com.badlogic.gdx.utils.JsonValue terminal, long generation) {
            try {
            require(longTrace && generation > 0, "Not a long exercise/generation");
            int count = 0; boolean found = false;
            for (com.badlogic.gdx.utils.JsonValue row : records) {
                if (row.getString("kind").equals("observation") && row.getString("phase", "").equals("step")) count++;
                if (row == terminal) { found = true; break; }
            }
            require(found && terminal.getString("kind").equals("observation") && terminal.getString("phase", "").equals("step")
                && terminal.has("generation") && terminal.get("generation").isLong() && terminal.getLong("generation") == generation,
                "Terminal must be an actual captured step in this generation");
            com.badlogic.gdx.utils.JsonValue sample = terminal.get("sample"), step = sample == null ? null : sample.get("stepIndex");
            require(step != null && step.isLong() && step.asLong() >= 0 && step.asLong() <= 2499
                && count > 0 && count <= 2500 && count == step.asLong() + 1,
                "Terminal is outside the 2500 contiguous business ticks");
            require(terminal.has("sequence") && terminal.get("sequence").isLong() && terminal.getLong("sequence") >= 0,
                "Terminal needs its actual integer sequence");
            long sequence = terminal.getLong("sequence");
            require(terminalSequence < 0 || terminalSequence == sequence, "Terminal boundary cannot be replaced");
            terminalSequence = sequence; endAtCount(count); tailBudget();
            } catch (IllegalArgumentException failure) { throw fault(failure, steps); }
        }
        public void applicationErrors(Failures errors) {
            while (errorCursor < records.size()) {
                com.badlogic.gdx.utils.JsonValue row = records.get(errorCursor++);
                String kind = row.getString("kind");
                if(expectedLoadArmed && kind.equals("load-request") && row.getLong("generation",-1)==1 &&
                    row.getString("mode","").equals("MEMORY") && row.has("shareAlliedVision") &&
                    row.get("shareAlliedVision").isBoolean() && !row.getBoolean("shareAlliedVision")) expectedLoadRequested=true;
                if(expectedLoadArmed && expectedLoadRequested && !expectedLoadSeen && kind.equals("load-error") &&
                    row.getLong("generation",-1)==1 && row.getString("message","").contains("p001-ground.tsx")) {
                    expectedLoadSeen=true;
                    continue;
                }
                if (kind.equals("cleanup-error") || kind.equals("load-error") || kind.equals("launch-failed"))
                    errors.business(new IllegalArgumentException("Application " + kind + " at sequence " + row.getLong("sequence",-1)));
            }
        }
        public void feed(byte[] chunk, long elapsedMillis, boolean closing) {
            if (elapsedMillis < 0 || elapsedMillis > (closing ? 3000 : longTrace ? 60000 : 15000))
                throw fault(new IllegalArgumentException("Observation time budget exceeded"), steps);
            bytes += chunk.length;
            if (bytes > 16L*1024*1024) throw fault(new IllegalArgumentException("Final observation byte budget exceeded"), steps);
            IllegalArgumentException first = bytes > 12L*1024*1024 && !closing
                ? fault(new IllegalArgumentException("Business observation byte budget exceeded"), steps) : null;
            for (byte value : chunk) {
                if (value != '\n') { pending.write(value); continue; }
                try {
                    String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(pending.toByteArray())).toString();
                    // JsonReader also accepts libGDX's minimal format. The wire is JSONL.
                    new JsonLineSyntax(text).validate();
                    com.badlogic.gdx.utils.JsonValue row = new com.badlogic.gdx.utils.JsonReader().parse(text);
                    if (!row.isObject() || !row.has("kind") || !row.get("kind").isString())
                        throw new IllegalArgumentException("Missing record kind");
                    SandboxKnowledgeView.validateRecord(row);
                    records.add(row);
                    if (row.getString("kind").equals("observation") && row.getString("phase","").equals("step")) steps++;
                    if (longTrace && java.util.List.of("cleanup-error", "load-error", "launch-failed").contains(row.getString("kind")))
                        fault(new IllegalArgumentException("Application " + row.getString("kind") + " at sequence " + row.getLong("sequence", -1)), steps);
                } catch (Exception failure) {
                    IllegalArgumentException malformed = fault(new IllegalArgumentException("Malformed complete JSONL line",failure), steps);
                    if (first == null) first = malformed;
                } finally { pending.reset(); }
            }
            if (first != null) throw first;
            tailBudget();
            if (!longTrace && steps > 500 && !closing) throw new IllegalArgumentException("Run cumulative step budget exceeded");
        }
        public void complete() {
            if (pending.size() != 0) throw new IllegalArgumentException("Unterminated final JSONL line");
            tailBudget();
            if (longTrace && firstFault != null) throw firstFault;
            if (longTrace && businessEndSteps < 0) throw new IllegalArgumentException("Long capture has no terminal/failure boundary");
            if (steps > (longTrace ? 3000 : 500) || bytes > 16L*1024*1024)
                throw new IllegalArgumentException("Final run evidence budget exceeded");
        }
    }
    /** Syntax-only guard for one JSON object; the existing JsonReader still builds values. */
    private static final class JsonLineSyntax {
        private final String text;
        private int at;
        private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile(
            "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
        JsonLineSyntax(String text) { this.text = text; }
        void validate() {
            space();
            require(at < text.length() && text.charAt(at)=='{', "JSONL record must be an object");
            value(0); space();
            require(at==text.length(), "Trailing JSONL data");
        }
        private void space() {
            while (at<text.length() && " \t\r\n".indexOf(text.charAt(at))>=0) at++;
        }
        private boolean take(char c) {
            space();
            if (at<text.length() && text.charAt(at)==c) { at++; return true; }
            return false;
        }
        private void string() {
            require(take('"'), "Quoted JSON string required");
            while (at<text.length()) {
                char c=text.charAt(at++);
                if (c=='"') return;
                require(c>=32, "Control character in JSON string");
                if (c=='\\') {
                    require(at<text.length(), "Incomplete JSON escape");
                    char escape=text.charAt(at++);
                    if (escape=='u') {
                        for (int i=0;i<4;i++) {
                            require(at<text.length() && "0123456789abcdefABCDEF".indexOf(text.charAt(at))>=0, "Invalid JSON unicode escape");
                            at++;
                        }
                    } else require("\"\\/bfnrt".indexOf(escape)>=0, "Invalid JSON escape");
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }
        private void value(int depth) {
            require(depth<=64, "JSON nesting limit exceeded");
            space(); require(at<text.length(), "Missing JSON value");
            char c=text.charAt(at);
            if (c=='{') {
                at++; if (take('}')) return;
                do { string(); require(take(':'), "Missing JSON colon"); value(depth+1); } while (take(','));
                require(take('}'), "Missing JSON object end");
            } else if (c=='[') {
                at++; if (take(']')) return;
                do { value(depth+1); } while (take(','));
                require(take(']'), "Missing JSON array end");
            } else if (c=='"') string();
            else if (text.startsWith("true",at)) at+=4;
            else if (text.startsWith("false",at)) at+=5;
            else if (text.startsWith("null",at)) at+=4;
            else {
                java.util.regex.Matcher number=NUMBER.matcher(text).region(at,text.length());
                require(number.lookingAt(), "Invalid JSON value");
                at=number.end();
            }
        }
    }
    private SandboxFunctionalOracle() {}
    public static final class Configuration {
        public final String mode, exercise;
        public final boolean shared;
        Configuration(String mode, boolean shared, String exercise) {
            this.mode = mode; this.shared = shared; this.exercise = exercise;
        }
    }
    public static Configuration parameters(Map<String,String> values) {
        if (!values.keySet().equals(java.util.Set.of("mode","shared","exercise")) ||
            !java.util.Set.of("NO_FOG","MEMORY","HARDCORE").contains(values.get("mode")) ||
            !java.util.Set.of("true","false").contains(values.get("shared")) ||
            !java.util.Set.of("controls","explore","retention","unreachable","pause","recreate","visibility","recovery").contains(values.get("exercise")) ||
            (values.get("exercise").equals("recovery") && (!values.get("mode").equals("MEMORY") || !values.get("shared").equals("false"))) ||
            (values.get("exercise").equals("unreachable") && !values.get("mode").equals("NO_FOG")) ||
            ((values.get("exercise").equals("retention") || values.get("exercise").equals("recreate") || values.get("exercise").equals("visibility")) &&
                (values.get("mode").equals("NO_FOG") || !values.get("shared").equals("false"))))
            throw new IllegalArgumentException("Exactly mode/shared/exercise finite values required");
        return new Configuration(values.get("mode"), Boolean.parseBoolean(values.get("shared")), values.get("exercise"));
    }
    private static void require(boolean value, String error) {
        if (!value) throw new IllegalArgumentException(error);
    }
    public static com.badlogic.gdx.utils.JsonValue unit(com.badlogic.gdx.utils.JsonValue sample, String id) {
        for (com.badlogic.gdx.utils.JsonValue unit : sample.get("units"))
            if (unit.getString("unitId").equals(id)) return unit;
        throw new IllegalArgumentException("Missing player-observed unit " + id);
    }
    public static void initial(com.badlogic.gdx.utils.JsonValue sample, String mode, boolean shared) {
        require(sample.getString("scenarioId").equals("p002-knowledge") && sample.getString("playerId").equals("p1"), "Wrong scenario/player");
        require(sample.getLong("stepIndex") == 0 && sample.getDouble("simulationTime") == 0, "New match retained simulation time");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (com.badlogic.gdx.utils.JsonValue unit : sample.get("units"))
            require(ids.add(unit.getString("unitId")), "Duplicate initial unit");
        boolean all = mode.equals("NO_FOG") || shared;
        require(ids.equals(all ? java.util.Set.of("u1","u2","u3") : java.util.Set.of("u1")), "Wrong initial visible units");
        String[] names = all ? new String[]{"u1","u2","u3"} : new String[]{"u1"};
        double[] xs = {110,510,550}, ys = {110,310,310};
        for (int i=0; i<names.length; i++) {
            com.badlogic.gdx.utils.JsonValue value = unit(sample,names[i]);
            require(value.getString("ownerPlayerId").equals("p"+(i+1)) &&
                value.get("position").getDouble("x")==xs[i] && value.get("position").getDouble("y")==ys[i], "Wrong initial unit state");
            require(stopped(value) && value.getDouble("angularVelocity") == 0, "New match retained movement state");
        }
    }
    public static void configuration(com.badlogic.gdx.utils.JsonValue row, String mode, boolean shared) {
        require(row.getString("mode").equals(mode) && row.getBoolean("shareAlliedVision")==shared, "Load configuration mismatch");
    }
    public static long newGeneration(com.badlogic.gdx.utils.JsonValue request, long previous) {
        long generation = request.getLong("generation");
        require(request.getString("kind").equals("load-request") && previous >= 0 && generation > previous,
            "Expected a new load generation");
        return generation;
    }
    public static void target(com.badlogic.gdx.utils.JsonValue action, double x, double y,
                              double pixelWidth, double pixelHeight) {
        double actualX = action.get("target").getDouble("x");
        double actualY = action.get("target").getDouble("y");
        require(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(actualX) && Double.isFinite(actualY)
            && Double.isFinite(pixelWidth) && Double.isFinite(pixelHeight) && pixelWidth > 0 && pixelHeight > 0,
            "Invalid target or pixel footprint");
        require(Math.abs(actualX - x) <= pixelWidth && Math.abs(actualY - y) <= pixelHeight,
            "Move target differs from the injected touch");
    }
    public static boolean detour(com.badlogic.gdx.utils.JsonValue unit, double targetY, double pixelHeight) {
        require(Double.isFinite(targetY) && Double.isFinite(pixelHeight) && pixelHeight > 0, "Invalid detour geometry");
        com.badlogic.gdx.utils.JsonValue path = unit.get("pathPoints");
        if (path.size < 2) return false;
        for (int index = 0; index < path.size - 1; index++) {
            double y = path.get(index).getDouble("y");
            require(Double.isFinite(y), "Invalid route coordinate");
            if (Math.abs(y - targetY) > pixelHeight) return true;
        }
        return false;
    }
    public static void accepted(com.badlogic.gdx.utils.JsonValue row, String type) {
        require(row.getString("type").equals(type) && row.getString("unitId").equals("u1") &&
            row.getString("actorPlayerId").equals("p1") && row.get("result").getBoolean("accepted"), "Action rejected or wrong identity/type");
    }
    public static boolean moved(com.badlogic.gdx.utils.JsonValue unit, double x, double y) {
        return unit.get("position").getDouble("x")!=x || unit.get("position").getDouble("y")!=y;
    }
    public static void foreignAction(com.badlogic.gdx.utils.JsonValue row, String type, String id) {
        require(java.util.Set.of("u2", "u3").contains(id)
            && java.util.Set.of("SelectAction", "MoveAction", "StopAction").contains(type), "Invalid foreign action contract");
        require(row.getString("type").equals(type) && row.getString("unitId").equals(id)
            && row.getString("actorPlayerId").equals("p1"), "Wrong foreign action identity/type");
        com.badlogic.gdx.utils.JsonValue result = row.get("result");
        require(result != null && result.has("accepted") && result.get("accepted").isBoolean(),
            "Foreign action needs a boolean result");
        if (type.equals("SelectAction")) require(result.getBoolean("accepted"), "Visible foreign Select rejected");
        else require(!result.getBoolean("accepted") && result.getString("code", "").equals("not-owner"),
            "Foreign control was not rejected as not-owner");
    }
    public static void foreignUnchanged(com.badlogic.gdx.utils.JsonValue initial,
                                        com.badlogic.gdx.utils.JsonValue current, String id) {
        require(java.util.Set.of("u2", "u3").contains(id), "Invalid foreign unit");
        require(current.getString("worldId").equals(initial.getString("worldId"))
            && current.getString("scenarioId").equals("p002-knowledge") && current.getString("playerId").equals("p1"),
            "Foreign observation identity changed");
        for (com.badlogic.gdx.utils.JsonValue sample : new com.badlogic.gdx.utils.JsonValue[]{initial, current}) {
            int count = 0;
            for (com.badlogic.gdx.utils.JsonValue unit : sample.get("units"))
                if (unit.getString("unitId").equals(id)) count++;
            require(count == 1, "Missing or duplicate visible foreign unit");
        }
        com.badlogic.gdx.utils.JsonValue before = unit(initial, id), after = unit(current, id);
        require(before.getString("ownerPlayerId").equals(id.equals("u2") ? "p2" : "p3"), "Wrong foreign owner");
        require(before.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)
            .equals(after.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json)), "Foreign public unit state changed");
    }
    public static boolean foreignAfterAction(java.util.List<com.badlogic.gdx.utils.JsonValue> rows,
            com.badlogic.gdx.utils.JsonValue action, com.badlogic.gdx.utils.JsonValue initial, String id) {
        for (com.badlogic.gdx.utils.JsonValue row : rows)
            if (row.getString("kind").equals("observation") && row.getString("phase", "").equals("after-action")
                && row.getLong("generation", -1) == action.getLong("generation")
                && row.getLong("sequence") > action.getLong("sequence")) {
                foreignUnchanged(initial, row.get("sample"), id);
                return true;
            }
        return false;
    }
    public static boolean stopped(com.badlogic.gdx.utils.JsonValue unit) {
        return unit.get("moveTarget").isNull() && unit.get("pathPoints").size==0 && unit.getDouble("speed")==0;
    }
    /** Ordered, TEST-only check of fixed cell (5,5) through the normal close tail. */
    public static final class Retention {
        private final String mode, world;
        private final long generation, gid;
        private final boolean blocked;
        private final double initialX, initialY;
        private long lastSequence, lastStep = -1, firstLossStep = -1;
        private double lastTime;
        private boolean moved, failed;
        private final java.util.List<Long> lossSequences = new java.util.ArrayList<>();
        private final java.util.List<Long> lossSteps = new java.util.ArrayList<>();

        public Retention(com.badlogic.gdx.utils.JsonValue initial, String mode) {
            require(mode.equals("MEMORY") || mode.equals("HARDCORE"), "Retention needs a knowledge mode");
            require(initial.getString("kind").equals("observation") && initial.getString("phase").equals("initial"),
                "Retention needs the actual initial observation");
            this.mode = mode;
            generation = initial.getLong("generation");
            lastSequence = initial.getLong("sequence");
            com.badlogic.gdx.utils.JsonValue sample = initial.get("sample");
            world = sample.getString("worldId");
            identity(sample);
            require(sample.getLong("stepIndex") == 0 && sample.getDouble("simulationTime") == 0,
                "Retention initial must be a new match");
            com.badlogic.gdx.utils.JsonValue cell = cell(sample);
            require(cell.getBoolean("visible"), "Initial retention cell was not visible");
            known(cell);
            gid = cell.getLong("gid");
            blocked = cell.getBoolean("blocked");
            com.badlogic.gdx.utils.JsonValue position = position(sample);
            initialX = position.getDouble("x");
            initialY = position.getDouble("y");
        }
        private void identity(com.badlogic.gdx.utils.JsonValue sample) {
            require(sample.getString("worldId").equals(world) && sample.getString("playerId").equals("p1")
                && sample.getString("scenarioId").equals("p002-knowledge"), "Retention observation identity changed");
        }
        private static com.badlogic.gdx.utils.JsonValue cell(com.badlogic.gdx.utils.JsonValue sample) {
            com.badlogic.gdx.utils.JsonValue cell = SandboxKnowledgeView.sample(sample).get(155);
            require(cell != null && cell.isObject() && cell.has("visible") && cell.get("visible").isBoolean()
                && cell.has("gid") && cell.has("blocked"), "Malformed retention cell");
            return cell;
        }
        private static void known(com.badlogic.gdx.utils.JsonValue cell) {
            require(cell.get("gid").isLong() && cell.get("blocked").isBoolean(), "Visible cell must have typed knowledge");
        }
        private static com.badlogic.gdx.utils.JsonValue position(com.badlogic.gdx.utils.JsonValue sample) {
            com.badlogic.gdx.utils.JsonValue position = unit(sample, "u1").get("position");
            require(position != null && position.has("x") && position.has("y")
                && position.get("x").isNumber() && position.get("y").isNumber()
                && Double.isFinite(position.getDouble("x")) && Double.isFinite(position.getDouble("y")),
                "Retention requires finite observed position");
            return position;
        }
        public void accept(com.badlogic.gdx.utils.JsonValue row) {
            try {
                if (!row.getString("kind").equals("observation") || row.getLong("generation", -1) != generation
                    || !row.getString("phase", "").equals("step")) return;
                com.badlogic.gdx.utils.JsonValue sample = row.get("sample");
                identity(sample);
                require(row.get("sequence").isLong() && sample.get("stepIndex").isLong()
                    && sample.get("simulationTime").isNumber(), "Malformed retention observation order");
                long sequence = row.getLong("sequence"), step = sample.getLong("stepIndex");
                double time = sample.getDouble("simulationTime");
                require(sequence > lastSequence && step > lastStep && Double.isFinite(time) && time > lastTime,
                    "Duplicate or out-of-order retention observation");
                lastSequence = sequence; lastStep = step; lastTime = time;
                com.badlogic.gdx.utils.JsonValue position = position(sample);
                moved |= position.getDouble("x") != initialX || position.getDouble("y") != initialY;
                com.badlogic.gdx.utils.JsonValue cell = cell(sample);
                if (cell.getBoolean("visible")) {
                    known(cell);
                    lossSequences.clear(); lossSteps.clear();
                    return;
                }
                if (mode.equals("MEMORY")) {
                    known(cell);
                    require(cell.getLong("gid") == gid && cell.getBoolean("blocked") == blocked,
                        "Memory changed last-known terrain after loss");
                } else require(cell.get("gid").isNull() && cell.get("blocked").isNull(),
                    "Hardcore retained terrain after loss");
                if (firstLossStep < 0) firstLossStep = step;
                lossSequences.add(sequence); lossSteps.add(step);
                if (lossSteps.size() > 3) { lossSequences.remove(0); lossSteps.remove(0); }
            } catch (RuntimeException failure) {
                failed = true;
                throw failure;
            }
        }
        public boolean ready() { return !failed && moved && lossSteps.size() == 3; }
        public long firstLossStep() { return firstLossStep; }
        public java.util.List<Long> lossSequences() { return new java.util.ArrayList<>(lossSequences); }
        public java.util.List<Long> lossSteps() { return new java.util.ArrayList<>(lossSteps); }
    }
    /** Fixed NoFog cancellation scenario; this is not a general collision oracle. */
    public static final class Unreachable {
        private final long generation;
        private final String world;
        private final double commandX, commandY;
        private long sequence, step;
        private double time, stableX, stableY;
        private int stableSteps;
        private boolean afterAction, locked, failed;
        public Unreachable(com.badlogic.gdx.utils.JsonValue started, com.badlogic.gdx.utils.JsonValue before,
                           com.badlogic.gdx.utils.JsonValue cancelled) {
            events(started, "movement-started");
            events(cancelled, "movement-cancelled", "path-unreachable");
            generation = integer(cancelled, "generation");
            sequence = integer(cancelled, "sequence");
            require(integer(started, "generation") == generation && integer(before, "generation") == generation
                && integer(started, "sequence") < integer(before, "sequence")
                && integer(before, "sequence") + 1 == sequence, "Cancellation is not bound to its immediate before-action");
            require(before.getString("kind").equals("observation") && before.getString("phase").equals("before-action"),
                "Missing cancellation before-action");
            com.badlogic.gdx.utils.JsonValue sample = before.get("sample");
            require(sample.get("worldId").isString(), "Missing cancellation world");
            world = sample.getString("worldId");
            com.badlogic.gdx.utils.JsonValue unit = observed(sample);
            step = integer(sample, "stepIndex"); time = number(sample, "simulationTime");
            commandX = number(unit.get("position"), "x"); commandY = number(unit.get("position"), "y");
            require(number(unit, "speed") > 0 && (commandX != 110 || commandY != 110)
                && unit.get("moveTarget").isObject() && unit.get("pathPoints").size > 0, "Old movement was not active");
            require(number(unit.get("moveTarget"), "x") == number(started.get("target"), "x")
                && number(unit.get("moveTarget"), "y") == number(started.get("target"), "y"), "Active target is not the first actual Move");
        }
        private static long integer(com.badlogic.gdx.utils.JsonValue row, String name) {
            require(row != null && row.has(name) && row.get(name).isLong(), "Missing integer " + name);
            return row.getLong(name);
        }
        private static double number(com.badlogic.gdx.utils.JsonValue row, String name) {
            require(row != null && row.has(name) && row.get(name).isNumber() && Double.isFinite(row.getDouble(name)),
                "Missing finite number " + name);
            return row.getDouble(name);
        }
        private static void events(com.badlogic.gdx.utils.JsonValue row, String... kinds) {
            require(row.getString("kind").equals("action"), "Missing Move action");
            accepted(row, "MoveAction");
            com.badlogic.gdx.utils.JsonValue result = row.get("result");
            require(result.get("accepted").isBoolean() && result.get("events").isArray()
                && result.get("events").size == kinds.length, "Unexpected Move result");
            for (int i = 0; i < kinds.length; i++)
                require(result.get("events").get(i).getString("kind").equals(kinds[i])
                    && result.get("events").get(i).getString("unitId").equals("u1"), "Wrong Move event or order");
        }
        private com.badlogic.gdx.utils.JsonValue observed(com.badlogic.gdx.utils.JsonValue sample) {
            require(sample != null && sample.getString("worldId").equals(world)
                && sample.getString("scenarioId").equals("p002-knowledge") && sample.getString("playerId").equals("p1"),
                "Cancellation observation identity changed");
            int count = 0;
            for (com.badlogic.gdx.utils.JsonValue value : sample.get("units"))
                if (value.getString("unitId").equals("u1")) count++;
            require(count == 1, "Missing or duplicate cancellation unit");
            com.badlogic.gdx.utils.JsonValue unit = unit(sample, "u1");
            require(unit.getString("ownerPlayerId").equals("p1") && unit.has("moveTarget")
                && unit.has("pathPoints") && unit.get("pathPoints").isArray(), "Malformed own unit");
            double x = number(unit.get("position"), "x"), y = number(unit.get("position"), "y");
            require(x >= 11 && x <= 269 && y >= 11 && y <= 389, "Unit left the fixed route's safe rectangle");
            require(number(unit, "speed") >= 0, "Negative speed");
            number(unit, "angularVelocity");
            return unit;
        }
        public void accept(com.badlogic.gdx.utils.JsonValue row) {
            try {
                if (row.getLong("generation", -1) != generation || !row.getString("kind").equals("observation")) return;
                require(integer(row, "sequence") > sequence, "Duplicate or reordered cancellation observation");
                com.badlogic.gdx.utils.JsonValue sample = row.get("sample"), unit = observed(sample);
                long nextStep = integer(sample, "stepIndex");
                double nextTime = number(sample, "simulationTime");
                double x = number(unit.get("position"), "x"), y = number(unit.get("position"), "y");
                require(unit.get("moveTarget").isNull() && unit.get("pathPoints").size == 0, "Unreachable target/path was restored");
                require(sample.get("events").isArray(), "Missing cancellation events");
                for (com.badlogic.gdx.utils.JsonValue event : sample.get("events"))
                    require(!event.getString("kind").equals("movement-arrived") || !event.getString("unitId", "").equals("u1"),
                        "Unreachable command became an arrival");
                boolean isStep = row.getString("phase").equals("step");
                if (!afterAction) {
                    require(row.getString("phase").equals("after-action") && nextStep == step && nextTime == time
                        && x == commandX && y == commandY, "Missing immediate after-action or command teleported the unit");
                    afterAction = true;
                } else if (isStep) require(nextStep > step && nextTime > time, "Duplicate or reordered cancellation step");
                else require(nextStep == step && nextTime == time, "Unobserved advancement between cancellation samples");
                boolean still = number(unit, "speed") == 0 && number(unit, "angularVelocity") == 0;
                if (locked) require(still && x == stableX && y == stableY, "Unit moved after the stopped result");
                if (isStep && !locked) {
                    stableSteps = still ? (stableSteps > 0 && x == stableX && y == stableY ? stableSteps + 1 : 1) : 0;
                    stableX = x; stableY = y;
                    locked = stableSteps >= 3;
                }
                sequence = integer(row, "sequence"); step = nextStep; time = nextTime;
            } catch (RuntimeException failure) { failed = true; throw failure; }
        }
        public boolean ready() { return !failed && afterAction && locked; }
        public long lastSequence() { return sequence; }
        public long lastStep() { return step; }
    }
    public static final class Failures {
        public Throwable primary;
        public final java.util.List<Throwable> secondary = new java.util.ArrayList<>();
        public void business(Throwable error) { if (primary==null) primary=error; else secondary.add(error); }
        public void cleanup(Throwable error) { secondary.add(error); }
    }
}
