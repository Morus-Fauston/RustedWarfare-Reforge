package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class ComparisonTest {
    private val repo = Path.of(System.getProperty("repo.root"))
    private val fixture = repo.resolve("SimulationTest/fixtures/record-chain/scenario.json")

    @Test fun `T01 independent literal traces pass exact self contract`() {
        val expected = controlled()
        val actual = controlled()
        val result = TraceComparator.evaluate(scenario(), contract(), actual, expected)
        assertEquals(0, result.exitCode)
        assertEquals("completed", result.document.text("result"))
        assertEquals(JsonNull, result.document["firstDivergence"])
        assertEquals(false, result.document.objectAt("comparison")["originalCompatibility"]!!.jsonPrimitive.boolean)
    }

    @Test fun `T02 displacement ownership and input timing expose original and normalized context`() {
        for ((name, rows) in listOf(
            "displacement" to changeUnit(controlled(), "position", obj("x" to n(1.25), "y" to n(0))),
            "ownership" to changeUnit(controlled(), "ownerPlayerId", s("p2")),
            "input-timing" to controlled().map { if (it["kind"] == s("action-result"))
                JsonObject(it + ("atStep" to n(1))) else it },
        )) {
            val result = TraceComparator.evaluate(scenario(), contract(), rows, controlled())
            assertEquals(2, result.exitCode, name)
            val first = result.document.objectAt("firstDivergence")
            assertTrue(first.integer("line") in 3..4, name)
            assertTrue(first.objectAt("rawActual").isNotEmpty(), name)
            assertTrue(first.objectAt("normalizedActual").isNotEmpty(), name)
            assertTrue(first.arrayAt("actualContext").size >= 3, name)
            if (name == "ownership") {
                assertEquals(s("p2"), first.objectAt("rawActual").objectAt("observation").arrayAt("units")[0].jsonObject["ownerPlayerId"])
                assertEquals(s("p2"), first.objectAt("normalizedActual").objectAt("observation").arrayAt("units")[0].jsonObject["owner"])
            }
        }
    }

    @Test fun `T03 missing steps fields completion truncation crash and duplicates never pass`() {
        val base = controlled()
        val variants = listOf(
            "missing-step" to (base.take(4) + base.last()),
            "missing-field" to changeUnit(base, "speed", null),
            "missing-completion" to base.dropLast(1),
            "incomplete" to (base.dropLast(1) + JsonObject(base.last() + ("complete" to b(false)))),
            "crash" to (base.dropLast(1) + JsonObject(base.last() + mapOf("status" to s("execution-failed"), "exitCode" to n(4), "complete" to b(false)))),
            "duplicate-step" to (base.take(5) + base[4] + base.last()),
            "missing-object" to base.map { if (it["kind"] == s("observation")) JsonObject(it + ("observation" to JsonObject(it.objectAt("observation") + ("units" to array(emptyList()))))) else it },
        )
        for ((name, rows) in variants) {
            val result = TraceComparator.evaluate(scenario(), contract(), rows, base)
            assertEquals(if (name == "crash") 4 else 3, result.exitCode, name)
            assertEquals(JsonNull, result.document["firstDivergence"], name)
        }
    }

    @Test fun `empty rules unknown operators tolerances and compatibility scope cannot pass`() {
        assertEquals(3, TraceComparator.evaluate(scenario(), JsonObject(contract() + ("rules" to array(emptyList()))), controlled()).exitCode)
        for (invalid in listOf(
            JsonObject(contract() + ("scope" to s("original-compatibility"))),
            JsonObject(contract() + ("schemaVersion" to n(2))),
            JsonObject(contract() + ("comparison" to obj("numberMode" to s("declared-per-field"), "tolerances" to obj("speed" to obj("mode" to s("absolute"), "value" to n(1)))))),
        )) assertEquals(64, TraceComparator.evaluate(scenario(), invalid, controlled()).exitCode)
    }

    @Test fun `CLI runner evaluates predeclared stop rules and returns true judgment`() {
        val stop = obj("ruleId" to s("stop.cancels"), "when" to obj("event" to s("action-accepted"), "actionKind" to s("stop")),
            "assertions" to array(listOf(obj("path" to s("units.u1.moveTarget"), "operator" to s("absent-or-null")),
                obj("path" to s("events"), "operator" to s("contains"), "value" to s("movement-cancelled")))))
        val fixed = JsonObject(contract() + mapOf("scenarioIds" to array(listOf(s("tool.record-chain.v1"))), "rules" to array(listOf(stop))))
        val file = Files.createTempFile("fixed-contract-", ".json")
        Files.writeString(file, fixed.toString())
        val output = Files.createTempDirectory("evaluated-run-").resolve("run")
        val result = Runner.run(RunRequest(fixture, file, output, listOf("rule-test")))
        assertEquals(0, result.exitCode)
        assertEquals("completed", readObject(output.resolve("comparison/result.json")).text("result"))
        assertTrue(readObject(output.resolve("manifest.json")).objectAt("coverage")["ruleEvaluation"]!!.jsonPrimitive.boolean)
        assertEquals(0, TraceFiles.compare(fixture, file, output).exitCode)
    }

    @Test fun `action placement and order cannot shift behind a truthful atStep field`() {
        val base = controlled()
        val shifted = listOf(base[0], base[1], JsonObject(base[3] + ("observation" to JsonObject(base[3].objectAt("observation") +
            ("actionResults" to array(emptyList()))))), base[2], JsonObject(base[4] + ("observation" to JsonObject(base[4].objectAt("observation") +
            ("actionResults" to base[3].objectAt("observation").required("actionResults"))))), base[5])
        assertEquals(2, TraceComparator.evaluate(scenario(), contract(), shifted).exitCode)
    }

    @Test fun `every required nested field is independently checked`() {
        for (field in listOf("unitId", "ownerPlayerId", "typeId", "position", "heading", "speed", "angularVelocity", "moveTarget", "pathPoints", "movementState")) {
            assertEquals(3, TraceComparator.evaluate(scenario(), contract(), changeUnit(controlled(), field, null)).exitCode, field)
        }
        val invalid = changeUnit(controlled(), "speed", s("NaN"))
        assertEquals(3, TraceComparator.evaluate(scenario(), contract(), invalid).exitCode)
        val differentVersion = controlled().mapIndexed { i, row -> if (i == 3) JsonObject(row + ("schemaVersion" to n(2))) else row }
        assertEquals(64, TraceComparator.evaluate(scenario(), contract(), differentVersion).exitCode)
    }

    @Test fun `exact decimals preserve tiny differences and allow only equal numeric spellings`() {
        val spelling = changeUnit(controlled(), "position", obj("x" to JsonPrimitive(1.0), "y" to JsonPrimitive(0.0)))
        assertEquals(0, TraceComparator.evaluate(scenario(), contract(), spelling, controlled()).exitCode)
        val tiny = changeUnit(controlled(), "position", json.parseToJsonElement("{\"x\":1.00000000000000000001,\"y\":0}"))
        assertEquals(2, TraceComparator.evaluate(scenario(), contract(), tiny, controlled()).exitCode)
    }

    @Test fun `untriggered conditions and unsupported assertions never vacuously pass`() {
        val never = JsonObject(contract().arrayAt("rules")[0].jsonObject + ("when" to obj("event" to s("action-accepted"), "actionKind" to s("move"))))
        assertEquals(3, TraceComparator.evaluate(scenario(), JsonObject(contract() + ("rules" to array(listOf(never)))), controlled()).exitCode)
        val unsupported = JsonObject(never - "when" + ("assertions" to array(listOf(obj("path" to s("speed"), "operator" to s("guess"), "value" to n(0))))))
        assertEquals(64, TraceComparator.evaluate(scenario(), JsonObject(contract() + ("rules" to array(listOf(unsupported)))), controlled()).exitCode)
    }

    @Test fun `missing scenario declarations incomplete producer status and wrong initial state never pass`() {
        for (key in listOf("content", "players", "coordinateModel", "timeModel", "termination")) {
            assertEquals(64, TraceComparator.evaluate(JsonObject(scenario() - key), contract(), controlled()).exitCode, key)
        }
        assertEquals(64, TraceComparator.evaluate(JsonObject(scenario() + ("angleUnit" to s("radian"))), contract(), controlled()).exitCode)
        val steps = scenario().objectAt("termination").arrayAt("steps")
        val repeated = JsonObject(scenario() + ("termination" to JsonObject(scenario().objectAt("termination") +
            ("steps" to array(listOf(steps[0], steps[0]))))))
        assertEquals(64, TraceComparator.evaluate(repeated, contract(), controlled()).exitCode)
        val base = controlled()
        val insufficient = base.dropLast(1) + JsonObject(base.last() + mapOf("status" to s("evidence-insufficient"), "exitCode" to n(3)))
        assertEquals(3, TraceComparator.evaluate(scenario(), contract(), insufficient).exitCode)
        val initial = base[1].objectAt("observation")
        val unit = initial.arrayAt("units")[0].jsonObject
        val wrong = base.mapIndexed { i, row -> if (i != 1) row else JsonObject(row + ("observation" to JsonObject(initial +
            ("units" to array(listOf(JsonObject(unit + ("position" to obj("x" to n(99), "y" to n(0)))))))))) }
        assertEquals(2, TraceComparator.evaluate(scenario(), contract(), wrong).exitCode)
    }

    @Test fun `changing isolated input snapshot during execution is an input identity failure`() {
        val fixed = JsonObject(contract() + ("scenarioIds" to array(listOf(s("tool.record-chain.v1")))))
        val file = Files.createTempFile("identity-contract-", ".json")
        Files.writeString(file, fixed.toString())
        val output = Files.createTempDirectory("changed-snapshot-").resolve("run")
        var changed = false
        val result = Runner.run(RunRequest(fixture, file, output, listOf("identity-change"), cancelled = {
            if (!changed) {
                Files.writeString(output.resolve("inputs/scenario.json"), "\n", java.nio.file.StandardOpenOption.APPEND)
                changed = true
            }
            false
        }))
        assertEquals(64, result.exitCode)
        assertEquals(JsonNull, readObject(output.resolve("comparison/result.json"))["firstDivergence"])
    }

    @Test fun `dotted unit IDs bind to the actual object and absent only applies to a leaf`() {
        fun rename(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (_, field) -> rename(field) })
            is JsonArray -> array(value.map(::rename))
            else -> if (value == s("u1")) s("u.1") else value
        }
        val namedScenario = rename(scenario()).jsonObject
        val rule = obj("ruleId" to s("target.empty"), "assertions" to array(listOf(obj("path" to s("units.u.1.moveTarget"), "operator" to s("absent-or-null")))))
        val namedContract = JsonObject(contract() + ("rules" to array(listOf(rule))))
        val good = controlled().map { rename(it).jsonObject }
        assertEquals(0, TraceComparator.evaluate(namedScenario, namedContract, good).exitCode)
        val bad = changeUnit(controlled(), "moveTarget", obj("x" to n(9), "y" to n(0))).map { rename(it).jsonObject }
        assertEquals(2, TraceComparator.evaluate(namedScenario, namedContract, bad).exitCode)
        val unknown = JsonObject(rule + ("assertions" to array(listOf(obj("path" to s("units.nonexistent.moveTarget"), "operator" to s("absent-or-null"))))))
        assertEquals(3, TraceComparator.evaluate(namedScenario, JsonObject(namedContract + ("rules" to array(listOf(unknown)))), good).exitCode)
    }

    @Test fun `event order duplicates omission and time alignment are checked before assertions`() {
        fun events(names: List<String>): List<JsonObject> {
            val base = controlled()
            val values = names.map { obj("kind" to s(it), "unitId" to s("u1")) }
            val sample = JsonObject(base[3] + ("observation" to JsonObject(base[3].objectAt("observation") + ("events" to array(values)))))
            return base.take(3) + sample + values.mapIndexed { i, event -> record("event", "eventId" to s("e$i"),
                "sampleId" to s("step0"), "stepIndex" to n(0), "event" to event) } + base.drop(4)
        }
        val expected = events(listOf("event-a", "event-b"))
        assertEquals(0, TraceComparator.evaluate(scenario(), contract(), expected).exitCode)
        assertEquals(2, TraceComparator.evaluate(scenario(), contract(), events(listOf("event-b", "event-a")), expected).exitCode)
        assertEquals(3, TraceComparator.evaluate(scenario(), contract(), expected.filterIndexed { i, _ -> i != 4 }).exitCode)
        val duplicate = expected.mapIndexed { i, row -> if (i == 5) JsonObject(row + ("eventId" to s("e0"))) else row }
        assertEquals(3, TraceComparator.evaluate(scenario(), contract(), duplicate).exitCode)
        val time = controlled().mapIndexed { i, row -> if (i != 4) row else JsonObject(row +
            ("observation" to JsonObject(row.objectAt("observation") + ("simulationTime" to n(1))))) }
        assertEquals(3, TraceComparator.evaluate(scenario(), contract(), time).exitCode)
    }

    private fun changeUnit(rows: List<JsonObject>, key: String, value: JsonElement?): List<JsonObject> = rows.mapIndexed { index, row ->
        if (index != 3) row else {
            val observation = row.objectAt("observation")
            val unit = observation.arrayAt("units")[0].jsonObject
            JsonObject(row + ("observation" to JsonObject(observation + ("units" to array(listOf(
                JsonObject(if (value == null) unit - key else unit + (key to value))))))))
        }
    }

    internal fun scenario(): JsonObject = record("scenario", "scenarioId" to s("controlled.t01"),
        "content" to obj("map" to obj("id" to s("controlled.map"), "sha256" to s(digest("controlled map\n".toByteArray()))),
            "unitVariant" to obj("id" to s("tank"), "sha256" to s(digest("controlled unit\n".toByteArray()))), "dependencies" to array(emptyList())),
        "coordinateModel" to obj("id" to s("controlled.coordinate.v1"), "space" to s("2d"), "xAxis" to s("x-right"),
            "yAxis" to s("y-down"), "origin" to s("top-left"), "unit" to s("world-unit"), "valueType" to s("double"), "precision" to s("ieee754-binary64")),
        "timeModel" to obj("id" to s("controlled.time.v1"), "deltaUnit" to s("tick"), "stepRule" to s("fixed-explicit-delta"), "precision" to s("ieee754-binary64")),
        "angleUnit" to s("degree-clockwise-zero-up"),
        "players" to array(listOf(obj("playerId" to s("p1"), "control" to s("local")), obj("playerId" to s("p2"), "control" to s("none")))),
        "units" to array(listOf(obj("unitId" to s("u1"), "owner" to s("p1"), "type" to s("tank"),
            "position" to obj("x" to n(0), "y" to n(0)), "heading" to n(90)))),
        "actions" to array(listOf(obj("actionId" to s("a1"), "atStep" to n(0), "actor" to s("p1"),
            "kind" to s("stop"), "unitId" to s("u1")))),
        "termination" to obj("kind" to s("explicit-steps"), "required" to b(true), "steps" to array(listOf(obj("stepIndex" to n(0), "delta" to n(1), "deltaUnit" to s("tick")),
            obj("stepIndex" to n(1), "delta" to n(1), "deltaUnit" to s("tick"))))))

    internal fun contract(): JsonObject = record("behavior-contract", "contractId" to s("controlled.t01.contract.v1"),
        "scope" to s("self-behavior"), "scenarioIds" to array(listOf(s("controlled.t01"))),
        "rules" to array(listOf(obj("ruleId" to s("owner.preserved"), "assertions" to array(listOf(
            obj("path" to s("units.u1.owner"), "operator" to s("equals"), "value" to s("p1"))))))),
        "comparison" to obj("numberMode" to s("declared-per-field"), "tolerances" to obj()),
        "approval" to obj("status" to s("tool-fixture-only"), "source" to s("TEST-P001-003")),
        "exclusions" to array(listOf(s("original-compatibility"))))

    internal fun controlled(): List<JsonObject> {
        fun observation(sample: String, phase: String, step: Int, time: Int, x: Int, actions: List<JsonObject>) =
            record("observation", "phase" to s(phase), "observation" to obj(
                "sampleId" to s(sample), "worldId" to s("world-1"), "scenarioId" to s("controlled.t01"),
                "stepIndex" to n(step), "simulationTime" to n(time),
                "units" to array(listOf(obj("unitId" to s("u1"), "ownerPlayerId" to s("p1"), "typeId" to s("tank"),
                    "position" to obj("x" to n(x), "y" to n(0)), "heading" to n(90), "speed" to n(0),
                    "angularVelocity" to n(0), "moveTarget" to JsonNull, "pathPoints" to array(emptyList()),
                    "movementState" to s("IDLE")))), "events" to array(emptyList()), "actionResults" to array(actions)))
        val action = obj("actionId" to s("a1"), "status" to s("accepted"), "queuedForStep" to JsonNull, "events" to array(emptyList()))
        return listOf(
            record("run-start", "runId" to s("controlled.run"), "startedAt" to s("2026-10-17T00:00:00Z")),
            observation("initial", "initial", 0, 0, 0, emptyList()),
            record("action-result", "atStep" to n(0), "action" to scenario().arrayAt("actions")[0], "result" to action),
            observation("step0", "after-advance", 0, 1, 1, listOf(action)),
            observation("step1", "after-advance", 1, 2, 2, emptyList()),
            record("run-end", "status" to s("completed"), "exitCode" to n(0), "observedSteps" to n(2),
                "requiredSteps" to n(2), "complete" to b(true)),
        )
    }
}
