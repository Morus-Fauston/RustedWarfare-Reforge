package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlin.test.*
import kotlinx.serialization.json.*

/** Literal tool fixtures, never a gameplay baseline or an expectation extracted from a core run. */
class ComparisonEvidenceTest {
    @Test fun `file archives prove T01 T02 T03 with immutable expected input`() {
        val root = System.getenv("SIMULATION_COMPARISON_EVIDENCE")?.let(Path::of)
            ?: Files.createTempDirectory("comparison-evidence-").resolve("suite")
        Files.createDirectory(root)
        val fixtures = ComparisonTest()
        val scenario = fixtures.scenario()
        val contract = fixtures.contract()
        val scenarioPath = root.resolve("scenario.json").also { write(it, scenario) }
        val contractPath = root.resolve("contract.json").also { write(it, contract) }
        val base = fixtures.controlled()
        val reference = archive(root.resolve("T01-reference"), base, scenarioPath, contractPath)
        val referenceHash = sha256(reference.resolve("reforge/raw.jsonl"))
        val checks = mutableListOf<JsonObject>()
        fun check(name: String, rows: List<JsonObject>, exit: Int, mutate: (Path) -> Unit = {}) {
            val actual = archive(root.resolve(name), rows, scenarioPath, contractPath)
            mutate(actual)
            val result = TraceFiles.compare(scenarioPath, contractPath, actual, reference)
            write(actual.resolve("judgment.json"), result.document)
            checks += obj("case" to s(name), "expectedExitCode" to n(exit), "actualExitCode" to n(result.exitCode),
                "category" to result.document.objectAt("comparison").required("category"), "archive" to s(actual.toString()))
            write(root.resolve("$name-check.json"), checks.last())
            assertEquals(exit, result.exitCode, "$name: ${result.document}")
            if (exit in setOf(3, 4, 64)) assertEquals(JsonNull, result.document["firstDivergence"], name)
        }
        fun unit(key: String, value: JsonElement?) = base.mapIndexed { i, row ->
            if (i != 3) row else {
                val observation = row.objectAt("observation")
                val unit = observation.arrayAt("units")[0].jsonObject
                JsonObject(row + ("observation" to JsonObject(observation + ("units" to array(listOf(
                    JsonObject(if (value == null) unit - key else unit + (key to value))))))))
            }
        }
        check("T01-equivalent", fixtures.controlled(), 0)
        check("T02-displacement", unit("position", obj("x" to n(1.25), "y" to n(0))), 2)
        check("T02-ownership", unit("ownerPlayerId", s("p2")), 2)
        check("T02-input-timing", base.map { if (it["kind"] == s("action-result")) JsonObject(it + ("atStep" to n(1))) else it }, 2)
        check("T03-missing-step", base.take(4) + base.last(), 3)
        check("T03-missing-field", unit("speed", null), 3)
        check("T03-missing-completion", base.dropLast(1), 3)
        check("T03-truncated", base, 3) { directory ->
            // Only the freshly generated isolated copy is changed, retaining its old hash to expose truncation.
            Files.writeString(directory.resolve("reforge/raw.jsonl"), "{\"schemaVersion\":1,")
        }
        check("T03-crash", base.dropLast(1) + JsonObject(base.last() + mapOf("status" to s("execution-failed"), "exitCode" to n(4), "complete" to b(false))), 4)
        check("T03-incomplete", base.dropLast(1) + JsonObject(base.last() + ("complete" to b(false))), 3)
        check("T03-duplicate-step", base.take(5) + base[4] + base.last(), 3)
        check("T03-missing-manifest", base, 3) { Files.move(it.resolve("manifest.json"), it.resolve("manifest.withheld.json")) }
        check("T03-missing-exit", base.dropLast(1) + JsonObject(base.last() - "exitCode"), 3)
        check("T03-missing-start", base.drop(1), 3)
        check("T03-normalized-tamper", base, 3) { directory ->
            Files.writeString(directory.resolve("reforge/normalized.jsonl"), "{}\n")
            updateTraceHashes(directory)
        }
        check("T03-scenario-identity", base, 64) { directory ->
            val manifest = readObject(directory.resolve("manifest.json"))
            Files.writeString(directory.resolve("manifest.json"), JsonObject(manifest + ("scenario" to
                JsonObject(manifest.objectAt("scenario") + ("sha256" to s("0".repeat(64)))))).toString())
        }
        check("T03-malformed-valid-hash", base, 3) { directory ->
            Files.writeString(directory.resolve("reforge/raw.jsonl"), "{invalid}\n")
            updateTraceHashes(directory)
        }
        check("T03-no-final-newline", base, 3) { directory ->
            val path = directory.resolve("reforge/raw.jsonl")
            Files.writeString(path, Files.readString(path).trimEnd())
            updateTraceHashes(directory)
        }
        assertEquals(referenceHash, sha256(reference.resolve("reforge/raw.jsonl")))
        write(root.resolve("summary.json"), obj("cases" to array(checks), "referenceUnchanged" to b(true),
            "referenceRawSha256" to s(referenceHash), "schemaVersion" to n(1), "contractId" to contract.required("contractId"),
            "scenarioSha256" to s(sha256(scenarioPath)), "contractSha256" to s(sha256(contractPath)),
            "originalCompatibility" to b(false)))
    }

    private fun archive(path: Path, rows: List<JsonObject>, scenario: Path, contract: Path): Path {
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
        val source = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/src/test/kotlin/com/rustedwarfare/reforge/testing/ComparisonEvidenceTest.kt")
        write(path.resolve("manifest.json"), record("run-manifest", "runId" to s("controlled.run"), "ticket" to s("TEST-P001-003"),
            "startedAt" to s("2026-10-17T00:00:00Z"), "finishedAt" to s("2026-10-17T00:00:01Z"),
            "status" to (completion?.get("status") ?: s("evidence-insufficient")), "exitCode" to (completion?.get("exitCode") ?: n(3)),
            "complete" to (completion?.get("complete") ?: b(false)),
            "environment" to obj("kind" to s("controlled-literal-no-core-execution")),
            "tool" to obj("id" to s("controlled.literal.generator"), "sha256" to s(sha256(source))),
            "scenario" to obj("id" to s("controlled.t01"), "sha256" to s(sha256(scenario))),
            "contract" to obj("id" to s("controlled.t01.contract.v1"), "sha256" to s(sha256(contract))),
            "inputs" to array(readObject(scenario).objectAt("content").let { listOf(it.required("map"), it.required("unitVariant")) }),
            "commands" to array(listOf(s("Gradle :test --tests *ComparisonEvidenceTest*"))),
            "coverage" to obj("initialStateChecked" to b(true), "observedSteps" to n(2), "requiredSteps" to n(2)),
            "traceFiles" to traceHashes(path)))
        return path
    }
    private fun traceHashes(path: Path) = array(listOf("reforge/raw.jsonl", "reforge/normalized.jsonl").map {
        obj("path" to s(it), "sha256" to s(sha256(path.resolve(it))))
    })
    private fun updateTraceHashes(path: Path) {
        val manifest = readObject(path.resolve("manifest.json"))
        Files.writeString(path.resolve("manifest.json"), JsonObject(manifest + ("traceFiles" to traceHashes(path))).toString())
    }
    private fun write(path: Path, value: JsonObject) { Files.writeString(path, value.toString(), CREATE_NEW) }
}
