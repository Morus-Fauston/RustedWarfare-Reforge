package com.rustedwarfare.reforge.android;

import com.badlogic.gdx.utils.JsonValue;

/** SC07 on actual post-Move step observations. Continues checking the whole closing tail. */
public final class SandboxExploreOracle {
    private final long generation, commandSequence;
    private final String mode, world;
    private final double targetY, pixelHeight;
    private long sequence, step;
    private double time, arrivalX, arrivalY, arrivalSpeed;
    private long discoveryStep = -1;
    private boolean rerouted, failed;
    private JsonValue arrival;

    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
    private static long integer(JsonValue row, String name) {
        require(row != null && row.has(name) && row.get(name).isLong(), "SC07 missing integer " + name); return row.getLong(name);
    }
    private static double number(JsonValue row, String name) {
        require(row != null && row.has(name) && row.get(name).isNumber() && Double.isFinite(row.getDouble(name)), "SC07 invalid number " + name);
        return row.getDouble(name);
    }
    private static String string(JsonValue row, String name) {
        require(row != null && row.has(name) && row.get(name).isString(), "SC07 missing string " + name); return row.getString(name);
    }
    private JsonValue unit(JsonValue sample) {
        require(string(sample,"worldId").equals(world) && string(sample,"scenarioId").equals("p002-knowledge")
            && string(sample,"playerId").equals("p1"), "SC07 observation identity changed");
        JsonValue units = sample.get("units"), found = null;
        require(units != null && units.isArray(), "SC07 missing observed units");
        for (JsonValue unit : units) if (string(unit,"unitId").equals("u1")) {
            require(found == null, "SC07 duplicate u1"); found = unit;
        }
        require(found != null && string(found,"ownerPlayerId").equals("p1"), "SC07 missing own u1");
        number(found.get("position"),"x"); number(found.get("position"),"y");
        require(number(found,"speed") >= 0 && number(found,"speed") <= 1.1, "SC07 invalid speed");
        JsonValue path = found.get("pathPoints"); require(path != null && path.isArray(), "SC07 invalid path");
        for (JsonValue point : path) { number(point,"x"); number(point,"y"); }
        require(found.has("moveTarget"), "SC07 missing goal"); return found;
    }
    public SandboxExploreOracle(JsonValue move, JsonValue commanded, String mode, double pixelHeight) {
        require(java.util.List.of("NO_FOG","MEMORY","HARDCORE").contains(mode)
            && Double.isFinite(pixelHeight) && pixelHeight > 0, "Invalid SC07 configuration");
        SandboxFunctionalOracle.accepted(move,"MoveAction");
        generation = integer(move,"generation"); commandSequence = integer(move,"sequence");
        require(generation > 0 && commandSequence >= 0 && string(move,"kind").equals("action"), "Invalid SC07 command identity");
        require(string(commanded,"kind").equals("observation") && string(commanded,"phase").equals("after-action")
            && integer(commanded,"generation") == generation && integer(commanded,"sequence") == commandSequence + 1,
            "SC07 requires the immediately adjacent after-action observation");
        this.mode = mode; this.pixelHeight = pixelHeight; targetY = number(move.get("target"),"y");
        JsonValue sample = commanded.get("sample"); world = string(sample,"worldId"); require(!world.isEmpty(), "SC07 empty world");
        JsonValue unit = unit(sample), goal = unit.get("moveTarget");
        require(goal != null && goal.isObject() && number(goal,"x") == number(move.get("target"),"x")
            && number(goal,"y") == targetY, "SC07 command goal changed");
        require(number(unit.get("position"),"x") == 110 && number(unit.get("position"),"y") == 110 && number(unit,"speed") == 0,
            "SC07 did not start at the original stationary spawn");
        if (!mode.equals("NO_FOG")) {
            JsonValue path = unit.get("pathPoints");
            require(path.size == 1 && number(path.get(0),"x") == number(goal,"x") && number(path.get(0),"y") == targetY,
                "Unknown terrain did not begin with a direct goal");
            require(SandboxKnowledgeView.sample(sample).get(164).get("blocked").isNull(), "SC07 wall was known before exploration");
        }
        sequence = integer(commanded,"sequence"); step = integer(sample,"stepIndex"); time = number(sample,"simulationTime");
        require(step >= 0 && time >= 0 && (time == 0 ? step == 0 : time == step + 1.0), "SC07 command step/time mismatch");
        if (time == 0) step = -1; // The initial and first actual tick both publicly use index 0.
    }
    public void accept(JsonValue row) {
        try {
            long nextSequence = integer(row,"sequence");
            require(nextSequence > sequence, "SC07 record reordered/duplicated"); sequence = nextSequence;
            String kind = string(row,"kind");
            require(!java.util.List.of("cleanup-error","load-error","launch-failed").contains(kind),
                "Application " + kind + " at sequence " + nextSequence);
            require(!java.util.List.of("action","selection","load-request","load-ready").contains(kind), "SC07 issued a later command/reload");
            if (!kind.equals("observation")) return;
            require(integer(row,"generation") == generation, "SC07 generation changed");
            JsonValue sample = row.get("sample"), unit = unit(sample);
            String phase = string(row,"phase"); long nextStep = integer(sample,"stepIndex"); double nextTime = number(sample,"simulationTime");
            if (phase.equals("step")) {
                require(nextStep >= 0 && nextStep == step + 1 && nextTime > time && nextTime == time + 1, "SC07 step/time discontinuity");
                step = nextStep; time = nextTime;
            } else require(phase.equals("before-close") && nextStep == step && nextTime == time, "SC07 unexpected phase/paused advancement");
            if (arrival != null) {
                double x = number(unit.get("position"),"x"), y = number(unit.get("position"),"y"), speed = number(unit,"speed");
                require(unit.get("moveTarget").isNull() && unit.get("pathPoints").size == 0 && Math.hypot(x-350,y-110) <= 4,
                    "SC07 goal/path restored or left original arrival tolerance");
                // Arrival clears intent while the approved movement model can still decelerate.
                if (phase.equals("step")) require(speed <= arrivalSpeed && Math.hypot(x-arrivalX,y-arrivalY) <= 1.1 + 8*Math.ulp(1.1)
                    && (arrivalSpeed != 0 || (speed == 0 && x == arrivalX && y == arrivalY)), "SC07 invalid post-arrival deceleration");
                else require(speed == arrivalSpeed && x == arrivalX && y == arrivalY, "SC07 advanced while closing without a tick");
                arrivalX = x; arrivalY = y; arrivalSpeed = speed;
                return;
            }
            if (!phase.equals("step")) return;
            require(nextStep <= 2499, "SC07 exhausted its 2500 world ticks without arrival");
            if (!mode.equals("NO_FOG")) {
                JsonValue wall = SandboxKnowledgeView.sample(sample).get(164);
                if (discoveryStep < 0 && wall.getBoolean("visible") && wall.get("blocked").isBoolean() && wall.getBoolean("blocked"))
                    discoveryStep = nextStep;
                if (discoveryStep >= 0 && nextStep > discoveryStep)
                    rerouted |= SandboxFunctionalOracle.detour(unit,targetY,pixelHeight);
            }
            JsonValue events = sample.get("events"); require(events != null && events.isArray(), "SC07 missing events");
            for (JsonValue event : events) if (string(event,"kind").equals("movement-arrived") && string(event,"unitId").equals("u1")) {
                require(arrival == null, "Duplicate first arrival event");
                arrivalX = number(unit.get("position"),"x"); arrivalY = number(unit.get("position"),"y");
                require(unit.get("moveTarget").isNull() && unit.get("pathPoints").size == 0
                    && Math.hypot(arrivalX - 350,arrivalY - 110) <= 4, "SC07 did not arrive within the original intent tolerance");
                require(mode.equals("NO_FOG") || (discoveryStep >= 0 && rerouted), "SC07 arrived without discovery then later reroute");
                arrivalSpeed = number(unit,"speed"); arrival = row;
            }
        } catch (RuntimeException failure) { failed = true; throw failure; }
    }
    public boolean ready() { return !failed && arrival != null; }
    /** The actual instrumentation and host contract tests share this ordered drain boundary. */
    public int drain(SandboxFunctionalOracle.Stream stream, SandboxFunctionalOracle.Failures errors, int cursor) {
        require(cursor >= 0 && cursor <= stream.records.size(), "Invalid SC07 drain cursor");
        while (cursor < stream.records.size()) {
            JsonValue row = stream.records.get(cursor++);
            try {
                accept(row);
                if (ready() && stream.terminalSequence() < 0) stream.endBusinessAt(terminal(),generation);
            } catch (Throwable failure) {
                errors.business(failure);
                try { stream.failBusinessAt(failure,row); }
                catch (Throwable boundaryFailure) { if (boundaryFailure != failure) errors.business(boundaryFailure); }
            }
        }
        return cursor;
    }
    public JsonValue terminal() { require(ready(), "No qualified SC07 terminal"); return arrival; }
    public long discoveryStep() { return discoveryStep; }
    public boolean rerouted() { return rerouted; }
}
