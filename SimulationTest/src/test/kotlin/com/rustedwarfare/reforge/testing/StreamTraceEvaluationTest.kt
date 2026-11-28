package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlin.test.*
import kotlinx.serialization.json.*

class StreamTraceEvaluationTest {
    private val fixtures = ComparisonTest()

    @Test
    fun run_archive_does_not_retain_in_memory_records_list() {
        val fieldNames = RunArchive::class.java.declaredFields.map { it.name }
        assertFalse("records" in fieldNames, "RunArchive must not hold a full-history records list: $fieldNames")
        assertTrue(
            RunArchive::class.java.declaredFields.none { Collection::class.java.isAssignableFrom(it.type) },
            "RunArchive must not declare any Collection field: $fieldNames",
        )
    }

    @Test
    fun stream_evaluator_and_trace_files_match_pass_rejected_action_untriggered_truncation_and_dual_stream_divergence() {
        val root = Files.createTempDirectory("p014-stream-eval-")
        val scenario = fixtures.scenario()
        val contract = fixtures.contract()
        val base = fixtures.controlled()

        // 1. Normal pass via StreamEvaluator and dual-stream TraceFiles.compare
        val streamEval = TraceComparator.StreamEvaluator(scenario, contract, base.size)
        for (row in base) streamEval.accept(row, row, compareExpected = true)
        val passJudgment = streamEval.finish({ listTraceSource(base) }, { listTraceSource(base) })
        assertEquals(0, passJudgment.exitCode)
        assertEquals(b(true), passJudgment.document.objectAt("comparison")["traceCompared"])
        assertEquals(n(base.size), passJudgment.document.objectAt("comparison")["actualRecordCount"])
        assertEquals(n(base.size), passJudgment.document.objectAt("comparison")["expectedRecordCount"])

        val scenarioPath = root.resolve("scenario.json").also { Files.writeString(it, scenario.toString(), CREATE_NEW) }
        val contractPath = root.resolve("contract.json").also { Files.writeString(it, contract.toString(), CREATE_NEW) }
        val refDir = writeArchive(root.resolve("ref"), base, scenarioPath, contractPath)
        val actDir = writeArchive(root.resolve("act-pass"), base, scenarioPath, contractPath)
        assertEquals(passJudgment.document - "evidenceRefs", TraceFiles.compare(scenarioPath, contractPath, actDir, refDir).document - "evidenceRefs")

        // 2. Mid-run action-rejected rule trigger passes when matched, and untriggered rule fails with exitCode 3
        val rejectedResult = obj(
            "actionId" to s("a1"),
            "status" to s("rejected"),
            "error" to obj("code" to s("not-owner"), "message" to s("Rejected in test")),
        )
        val rejectedRows = listOf(
            base[0],
            base[1],
            record("action-result", "atStep" to n(0), "action" to scenario.arrayAt("actions")[0], "result" to rejectedResult),
            JsonObject(base[3] + ("observation" to JsonObject(base[3].objectAt("observation") + ("actionResults" to array(listOf(rejectedResult)))))),
            base[4],
            base[5],
        )
        val rejectedRule = obj(
            "ruleId" to s("reject.checked"),
            "when" to obj("event" to s("action-rejected"), "actionKind" to s("stop"), "actionId" to s("a1")),
            "assertions" to array(listOf(obj("path" to s("units.u1.owner"), "operator" to s("equals"), "value" to s("p1")))),
        )
        val rejectedContract = JsonObject(contract + ("rules" to array(listOf(rejectedRule))))
        assertEquals(0, TraceComparator.evaluate(scenario, rejectedContract, rejectedRows).exitCode)

        val untriggeredJudgment = TraceComparator.evaluate(scenario, rejectedContract, base)
        assertEquals(3, untriggeredJudgment.exitCode)
        assertEquals("untriggered-rule", untriggeredJudgment.document.objectAt("comparison").text("category"))

        // 3. Truncated / missing run-end and crash precedence over earlier assertion divergence
        val earlyDivergentMissingEnd = base.dropLast(1).mapIndexed { idx, row ->
            if (idx != 1) row else {
                val obs = row.objectAt("observation")
                val unit = obs.arrayAt("units")[0].jsonObject
                JsonObject(row + ("observation" to JsonObject(obs + ("units" to array(listOf(
                    JsonObject(unit + ("position" to obj("x" to n(42), "y" to n(0)))),
                ))))))
            }
        }
        val missingEndJudgment = TraceComparator.evaluate(scenario, contract, earlyDivergentMissingEnd)
        assertEquals(3, missingEndJudgment.exitCode)
        assertEquals("missing-completion", missingEndJudgment.document.objectAt("comparison").text("category"))
        assertEquals(JsonNull, missingEndJudgment.document["firstDivergence"])

        val earlyDivergentCrash = earlyDivergentMissingEnd + JsonObject(
            base.last() + mapOf("status" to s("execution-failed"), "exitCode" to n(4), "complete" to b(false)),
        )
        val crashJudgment = TraceComparator.evaluate(scenario, contract, earlyDivergentCrash)
        assertEquals(4, crashJudgment.exitCode)
        assertEquals(JsonNull, crashJudgment.document["firstDivergence"])

        // 4. Dual-stream expected trace divergence via TraceFiles.compare produces bounded context window
        val driftedRows = base.mapIndexed { idx, row ->
            if (idx != 4) row else {
                val obs = row.objectAt("observation")
                val unit = obs.arrayAt("units")[0].jsonObject
                JsonObject(row + ("observation" to JsonObject(obs + ("units" to array(listOf(
                    JsonObject(unit + ("position" to obj("x" to n(99), "y" to n(0)))),
                ))))))
            }
        }
        val driftedDir = writeArchive(root.resolve("act-drift"), driftedRows, scenarioPath, contractPath)
        val fileDivergence = TraceFiles.compare(scenarioPath, contractPath, driftedDir, refDir)
        assertEquals(2, fileDivergence.exitCode)
        assertEquals(s("trace.exact"), fileDivergence.document["ruleId"])
        val firstDiv = fileDivergence.document.objectAt("firstDivergence")
        assertEquals(5L, firstDiv.integer("line"))
        assertEquals(3, firstDiv.arrayAt("actualContext").size)
        assertEquals(3, firstDiv.arrayAt("expectedContext").size)
    }

    @Test
    fun stream_evaluator_preserves_test_ui004_open_unit_task_validation_inside_full_maintenance_trace() {
        val baseScenario = fixtures.scenario()
        val maintenanceScenario = JsonObject(
            baseScenario + mapOf(
                "content" to JsonObject(baseScenario.objectAt("content") + (
                    "unitVariant" to obj("id" to s("builder"), "loader" to s("builder"), "sha256" to s(digest("controlled unit\n".toByteArray())))
                )),
                "units" to array(listOf(obj(
                    "unitId" to s("u1"), "owner" to s("p1"), "type" to s("builder"),
                    "position" to obj("x" to n(0), "y" to n(0)), "heading" to n(90),
                ))),
                "maintenanceContract" to obj("basePrices" to obj("builder" to n(500))),
            ),
        )
        val contract = fixtures.contract()

        fun workerObs(sample: String, phase: String, step: Int, time: Int, tasks: List<JsonObject>, actions: List<JsonObject>): JsonObject {
            val workerUnit = obj(
                "unitId" to s("u1"), "ownerPlayerId" to s("p1"), "typeId" to s("builder"),
                "position" to obj("x" to n(0), "y" to n(0)), "heading" to n(90),
                "speed" to n(0), "angularVelocity" to n(0), "moveTarget" to JsonNull,
                "pathPoints" to array(emptyList()), "movementState" to s("IDLE"),
                "constructionProgress" to n(1.0), "constructionTargetId" to JsonNull,
                "tasks" to array(tasks),
            )
            val workerDiag = obj(
                "unitId" to s("u1"), "elapsed" to n(0.0), "preparation" to n(0.0),
                "retryTime" to n(0), "singleNodePathCount" to n(0), "qBeforeDispatch" to n(0),
                "working" to b(false), "previousTargetActive" to b(false), "movementRequested" to b(false),
                "logicalRequestX" to JsonNull, "logicalRequestY" to JsonNull,
                "pendingPathNodeCounts" to array(emptyList()), "consumedPathNodeCounts" to array(emptyList()),
            )
            return record("observation", "phase" to s(phase), "observation" to obj(
                "sampleId" to s(sample), "worldId" to s("world-1"), "scenarioId" to s("controlled.t01"),
                "stepIndex" to n(step), "simulationTime" to n(time),
                "units" to array(listOf(workerUnit)),
                "reclaimedUnitIds" to array(emptyList()),
                "construction" to obj("clock" to n(time), "buildings" to array(emptyList()), "workers" to array(listOf(workerDiag)), "nonces" to obj()),
                "events" to array(emptyList()),
                "actionResults" to array(actions),
            ))
        }

        val openGuardTask = obj(
            "kind" to s("GUARD"),
            "actionId" to s("guard-1"),
            "target" to obj("x" to n(32.0), "y" to n(48.0)),
            "targetUnitId" to s("ally-1"),
            "atStep" to n(0),
            "buildingTypeId" to JsonNull,
            "buildingNonce" to JsonNull,
            "automaticRepair" to b(false),
            "extraWorkRange" to n(0.0),
            "guardLeash" to n(120.0),
        )
        val actionRes = obj("actionId" to s("a1"), "status" to s("accepted"), "queuedForStep" to JsonNull, "events" to array(emptyList()))
        val validTrace = listOf(
            record("run-start", "runId" to s("open.task.run"), "startedAt" to s("2026-11-28T00:00:00Z")),
            workerObs("initial", "initial", 0, 0, emptyList(), emptyList()),
            record("action-result", "atStep" to n(0), "action" to maintenanceScenario.arrayAt("actions")[0], "result" to actionRes),
            workerObs("step0", "after-advance", 0, 1, listOf(openGuardTask), listOf(actionRes)),
            workerObs("step1", "after-advance", 1, 2, listOf(openGuardTask), emptyList()),
            record("run-end", "status" to s("completed"), "exitCode" to n(0), "observedSteps" to n(2), "requiredSteps" to n(2), "complete" to b(true)),
        )
        assertEquals(0, TraceComparator.evaluate(maintenanceScenario, contract, validTrace).exitCode)

        val badExtTask = JsonObject(openGuardTask + ("InvalidUpperKey" to s("bad")))
        val badTrace = validTrace.mapIndexed { idx, row ->
            if (idx != 3) row else workerObs("step0", "after-advance", 0, 1, listOf(badExtTask), listOf(actionRes))
        }
        val badJudgment = TraceComparator.evaluate(maintenanceScenario, contract, badTrace)
        assertEquals(3, badJudgment.exitCode)
        assertEquals("maintenance-tasks", badJudgment.document.objectAt("comparison").text("category"))
    }

    private fun writeArchive(path: Path, rows: List<JsonObject>, scenario: Path, contract: Path): Path {
        Files.createDirectory(path)
        Files.createDirectory(path.resolve("inputs")); Files.createDirectory(path.resolve("reforge"))
        Files.copy(scenario, path.resolve("inputs/scenario.json")); Files.copy(contract, path.resolve("inputs/contract.json"))
        Files.writeString(path.resolve("reforge/raw.jsonl"), rows.joinToString("\n", postfix = "\n"), CREATE_NEW)
        val standard = rows.mapIndexed { index, row ->
            if (row["kind"] == s("run-start")) normalizeRecord(row, index + 1)
            else normalizeRecord(row, index + 1, "controlled.coordinate.v1", "controlled.time.v1")
        }
        Files.writeString(path.resolve("reforge/normalized.jsonl"), standard.joinToString("\n", postfix = "\n"), CREATE_NEW)
        val completion = rows.lastOrNull { it["kind"] == s("run-end") }
        write(path.resolve("manifest.json"), record(
            "run-manifest", "runId" to s("controlled.run"), "ticket" to s("TEST-P014-001"),
            "startedAt" to s("2026-10-17T00:00:00Z"), "finishedAt" to s("2026-10-17T00:00:01Z"),
            "status" to (completion?.get("status") ?: s("evidence-insufficient")),
            "exitCode" to (completion?.get("exitCode") ?: n(3)),
            "complete" to (completion?.get("complete") ?: b(false)),
            "environment" to obj("kind" to s("controlled-stream-test")),
            "tool" to obj("id" to s("controlled.stream.generator"), "sha256" to s("a".repeat(64))),
            "scenario" to obj("id" to s("controlled.t01"), "sha256" to s(sha256(scenario))),
            "contract" to obj("id" to s("controlled.t01.contract.v1"), "sha256" to s(sha256(contract))),
            "inputs" to array(readObject(scenario).objectAt("content").let { listOf(it.required("map"), it.required("unitVariant")) }),
            "commands" to array(listOf(s("StreamTraceEvaluationTest"))),
            "coverage" to obj("initialStateChecked" to b(true), "observedSteps" to n(2), "requiredSteps" to n(2)),
            "traceFiles" to array(listOf("reforge/raw.jsonl", "reforge/normalized.jsonl").map {
                obj("path" to s(it), "sha256" to s(sha256(path.resolve(it))))
            }),
        ))
        return path
    }

    private fun write(path: Path, value: JsonObject) {
        Files.writeString(path, value.toString(), CREATE_NEW)
    }
}
