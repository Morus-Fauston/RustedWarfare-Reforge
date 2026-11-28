package com.rustedwarfare.reforge.testing

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Random
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BatchScenarioWorkerParityTest {
    private fun locatePublicRoot(): Path {
        val cwd = Path.of("").toAbsolutePath().normalize()
        val direct = cwd.resolve("public")
        if (Files.isDirectory(direct.resolve("scenarios"))) return direct
        val nested = cwd.resolve("SimulationTest/public")
        if (Files.isDirectory(nested.resolve("scenarios"))) return nested
        error("Cannot locate SimulationTest/public from $cwd")
    }

    private fun stripRunMetadata(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement =
        when (element) {
            is JsonObject -> JsonObject(
                element.entries
                    .filter { (k, _) -> k != "runId" && k != "startedAt" && k != "finishedAt" }
                    .associate { (k, v) -> k to stripRunMetadata(v) }
            )
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(element.map(::stripRunMetadata))
            else -> element
        }

    private fun canonicalizeJsonLinesWithoutRunIdentity(path: Path): Pair<Int, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        Files.newBufferedReader(path, StandardCharsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val parsed = json.parseToJsonElement(line).jsonObject
                val cleaned = stripRunMetadata(parsed)
                digest.update(cleaned.toString().toByteArray(StandardCharsets.UTF_8))
                digest.update('\n'.code.toByte())
                count += 1
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return count to hex
    }

    @Test
    fun cold_single_runs_match_shuffled_batch_worker_across_core_formats_session_packs_and_failures() {
        val publicRoot = locatePublicRoot()
        val tempRoot = Files.createTempDirectory("batch-worker-parity-").toAbsolutePath().normalize()
        val savedOut = System.out
        val savedErr = System.err
        try {
            // Prepare one deliberate contract failure alongside valid INI/JSON/HJSON core & session cases
            val badContractPath = tempRoot.resolve("deliberate-bad-contract.json")
            val origContract = json.parseToJsonElement(
                Files.readString(publicRoot.resolve("contracts/content-three-formats-ini.json"))
            ).jsonObject.toMutableMap()
            val mutatedRules = origContract["rules"]!!.let { rulesElem ->
                val list = rulesElem as kotlinx.serialization.json.JsonArray
                val firstRule = list[0].jsonObject.toMutableMap()
                val assertions = (firstRule["assertions"]!! as kotlinx.serialization.json.JsonArray).toMutableList()
                val firstAssertion = assertions[0].jsonObject.toMutableMap()
                firstAssertion["value"] = n(9999)
                assertions[0] = JsonObject(firstAssertion)
                firstRule["assertions"] = kotlinx.serialization.json.JsonArray(assertions)
                kotlinx.serialization.json.JsonArray(listOf(JsonObject(firstRule)) + list.drop(1))
            }
            origContract["rules"] = mutatedRules
            Files.writeString(badContractPath, JsonObject(origContract).toString())

            data class CoreSpec(val label: String, val scenario: Path, val contract: Path, val expectedExit: Int)
            data class SessionSpec(val label: String, val inputs: Path, val plan: Path)

            fun sessionSpec(label: String, relativePlan: String): SessionSpec {
                val planPath = publicRoot.resolve(relativePlan).toAbsolutePath().normalize()
                val planDoc = json.parseToJsonElement(Files.readString(planPath)).jsonObject
                val relInputs = planDoc["inputs"]!!.jsonPrimitive.content
                val inputsPath = planPath.parent.resolve(relInputs).normalize()
                return SessionSpec(label, inputsPath, planPath)
            }

            val coreSpecs = listOf(
                CoreSpec(
                    "core-ini",
                    publicRoot.resolve("scenarios/content-three-formats-ini.json"),
                    publicRoot.resolve("contracts/content-three-formats-ini.json"),
                    0,
                ),
                CoreSpec(
                    "core-json",
                    publicRoot.resolve("scenarios/content-three-formats-json.json"),
                    publicRoot.resolve("contracts/content-three-formats-json.json"),
                    0,
                ),
                CoreSpec(
                    "core-hjson",
                    publicRoot.resolve("scenarios/content-three-formats-hjson.json"),
                    publicRoot.resolve("contracts/content-three-formats-hjson.json"),
                    0,
                ),
                CoreSpec(
                    "core-prod-combat",
                    publicRoot.resolve("scenarios/content-three-formats-production-combat-short-json.json"),
                    publicRoot.resolve("contracts/content-three-formats-production-combat-short-json.json"),
                    0,
                ),
                CoreSpec(
                    "core-build-failure",
                    publicRoot.resolve("scenarios/content-three-formats-economy-build-failure-short-json.json"),
                    publicRoot.resolve("contracts/content-three-formats-economy-build-failure-short-json.json"),
                    0,
                ),
                CoreSpec(
                    "core-deliberate-fail",
                    publicRoot.resolve("scenarios/content-three-formats-ini.json"),
                    badContractPath,
                    2,
                ),
            )

            val sessionSpecs = listOf(
                sessionSpec("sess-production", "platform/scenarios/production.json"),
                sessionSpec("sess-pack-ini", "platform/scenarios/content-pack-ini-short.json"),
                sessionSpec("sess-pack-json", "platform/scenarios/content-pack-json-short.json"),
                sessionSpec("sess-pack-hjson", "platform/scenarios/content-pack-hjson-short.json"),
            )

            data class CoreBaseline(
                val exitCode: Int,
                val status: String,
                val judgmentDoc: String,
                val traceSummary: Pair<Int, String>,
                val archivePath: Path,
            )

            val coreBaselines = coreSpecs.associate { spec ->
                val outDir = tempRoot.resolve("cold-${spec.label}")
                val res = Runner.run(
                    RunRequest(
                        scenario = spec.scenario,
                        contract = spec.contract,
                        output = outDir,
                        command = listOf("cold", spec.label),
                        timeoutMillis = 60_000L,
                    )
                )
                assertEquals(spec.expectedExit, res.exitCode, "Unexpected cold exit code for ${spec.label}")
                val compText = stripRunMetadata(
                    json.parseToJsonElement(Files.readString(outDir.resolve("comparison/result.json")))
                ).toString()
                val traceSummary = canonicalizeJsonLinesWithoutRunIdentity(outDir.resolve("reforge/normalized.jsonl"))
                assertTrue(traceSummary.first > 0)
                spec.label to CoreBaseline(res.exitCode, res.status, compText, traceSummary, outDir)
            }

            val sessionBaselines = sessionSpecs.associate { spec ->
                val outDir = tempRoot.resolve("cold-${spec.label}")
                PlatformSessionRunner.run(spec.inputs, spec.plan, outDir, 30)
                val diagSummary = canonicalizeJsonLinesWithoutRunIdentity(outDir.resolve("client-diagnostic.jsonl"))
                assertTrue(diagSummary.first > 0)
                spec.label to diagSummary
            }

            // Drive BatchScenarioWorker over real pipes across 2 shuffled rounds (including core-compare & failures)
            val reqIn = PipedInputStream(65536)
            val reqOut = PipedOutputStream(reqIn)
            val respIn = PipedInputStream(65536)
            val respOut = PipedOutputStream(respIn)

            val workerThread = thread(start = true, name = "batch-parity-worker") {
                BufferedReader(InputStreamReader(reqIn, StandardCharsets.UTF_8)).use { reader ->
                    PrintStream(respOut, true, StandardCharsets.UTF_8).use { writer ->
                        BatchScenarioWorker.serve(reader, writer, sessionOnly = false)
                    }
                }
            }

            try {
                OutputStreamWriter(reqOut, StandardCharsets.UTF_8).use { sender ->
                    BufferedReader(InputStreamReader(respIn, StandardCharsets.UTF_8)).use { receiver ->
                        val rng = Random(20261128L)
                        for (round in 1..2) {
                            val jobs = mutableListOf<() -> Unit>()
                            for (spec in coreSpecs) {
                                jobs += {
                                    val id = "r$round-${spec.label}"
                                    val baseOut = tempRoot.resolve("batch-$id")
                                    val logDir = tempRoot.resolve("logs-$id")
                                    val payload = obj(
                                        "id" to s(id),
                                        "cmd" to s("core-run"),
                                        "scenario" to s(spec.scenario.toString()),
                                        "contract" to s(spec.contract.toString()),
                                        "output" to s(baseOut.toString()),
                                        "stdoutLog" to s(logDir.resolve("stdout.log").toString()),
                                        "stderrLog" to s(logDir.resolve("stderr.log").toString()),
                                    )
                                    sender.write(payload.toString() + "\n")
                                    sender.flush()
                                    val replyLine = assertNotNull(receiver.readLine(), "Worker closed prematurely on $id")
                                    val reply = json.parseToJsonElement(replyLine).jsonObject
                                    val expected = coreBaselines.getValue(spec.label)
                                    assertEquals(id, reply["id"]!!.jsonPrimitive.content)
                                    assertEquals(expected.exitCode, reply["exitCode"]!!.jsonPrimitive.int, "Exit mismatch for $id")
                                    assertEquals(expected.exitCode == 0, reply["ok"]!!.jsonPrimitive.boolean)
                                    assertEquals(expected.status, reply["status"]!!.jsonPrimitive.content)
                                    val runDir = Path.of(reply["runOutput"]!!.jsonPrimitive.content)
                                    assertEquals(
                                        expected.judgmentDoc,
                                        stripRunMetadata(
                                            json.parseToJsonElement(Files.readString(runDir.resolve("comparison/result.json")))
                                        ).toString(),
                                        "Judgment differed on $id",
                                    )
                                    assertEquals(
                                        expected.traceSummary,
                                        canonicalizeJsonLinesWithoutRunIdentity(runDir.resolve("reforge/normalized.jsonl")),
                                        "Normalized trace differed on $id",
                                    )
                                }
                            }
                            // Also interleave core-compare in the same persistent worker against cold TraceFiles.compare
                            jobs += {
                                val spec = coreSpecs.first()
                                val expected = coreBaselines.getValue(spec.label)
                                val coldCompareDoc = stripRunMetadata(
                                    TraceFiles.compare(spec.scenario, spec.contract, expected.archivePath).document
                                ).toString()
                                val id = "r$round-compare-${spec.label}"
                                val compBase = tempRoot.resolve("batch-$id")
                                val logDir = tempRoot.resolve("logs-$id")
                                val payload = obj(
                                    "id" to s(id),
                                    "cmd" to s("core-compare"),
                                    "scenario" to s(spec.scenario.toString()),
                                    "contract" to s(spec.contract.toString()),
                                    "actual" to s(expected.archivePath.toString()),
                                    "output" to s(compBase.toString()),
                                    "stdoutLog" to s(logDir.resolve("stdout.log").toString()),
                                    "stderrLog" to s(logDir.resolve("stderr.log").toString()),
                                )
                                sender.write(payload.toString() + "\n")
                                sender.flush()
                                val reply = json.parseToJsonElement(assertNotNull(receiver.readLine())).jsonObject
                                assertEquals(0, reply["exitCode"]!!.jsonPrimitive.int)
                                val compRun = Path.of(reply["runOutput"]!!.jsonPrimitive.content)
                                assertEquals(
                                    coldCompareDoc,
                                    stripRunMetadata(
                                        json.parseToJsonElement(Files.readString(compRun.resolve("comparison/result.json")))
                                    ).toString(),
                                )
                            }
                            for (spec in sessionSpecs) {
                                jobs += {
                                    val id = "r$round-${spec.label}"
                                    val sessOut = tempRoot.resolve("batch-$id")
                                    val logDir = tempRoot.resolve("logs-$id")
                                    val payload = obj(
                                        "id" to s(id),
                                        "cmd" to s("session-run"),
                                        "inputs" to s(spec.inputs.toString()),
                                        "plan" to s(spec.plan.toString()),
                                        "output" to s(sessOut.toString()),
                                        "loadTimeoutSeconds" to n(30),
                                        "stdoutLog" to s(logDir.resolve("stdout.log").toString()),
                                        "stderrLog" to s(logDir.resolve("stderr.log").toString()),
                                    )
                                    sender.write(payload.toString() + "\n")
                                    sender.flush()
                                    val reply = json.parseToJsonElement(assertNotNull(receiver.readLine())).jsonObject
                                    assertEquals(id, reply["id"]!!.jsonPrimitive.content)
                                    assertTrue(reply["ok"]!!.jsonPrimitive.boolean, "Session failed on $id: $reply")
                                    assertEquals(0, reply["exitCode"]!!.jsonPrimitive.int)
                                    assertEquals(
                                        sessionBaselines.getValue(spec.label),
                                        canonicalizeJsonLinesWithoutRunIdentity(sessOut.resolve("client-diagnostic.jsonl")),
                                        "Session diagnostic differed on $id",
                                    )
                                }
                            }
                            jobs.shuffle(rng)
                            for (job in jobs) job()
                        }
                    }
                }
                workerThread.join(10_000)
                assertFalse(workerThread.isAlive, "BatchScenarioWorker thread did not exit cleanly on EOF")
            } finally {
                System.setOut(savedOut)
                System.setErr(savedErr)
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
            tempRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun platform_session_runner_batch_mode_rejects_non_session_commands_and_recovers_for_next_session() {
        val publicRoot = locatePublicRoot()
        val tempRoot = Files.createTempDirectory("session-only-worker-").toAbsolutePath().normalize()
        val savedOut = System.out
        val savedErr = System.err
        try {
            val reqIn = PipedInputStream(16384)
            val reqOut = PipedOutputStream(reqIn)
            val respIn = PipedInputStream(16384)
            val respOut = PipedOutputStream(respIn)
            val workerThread = thread(start = true, name = "session-only-worker") {
                BufferedReader(InputStreamReader(reqIn, StandardCharsets.UTF_8)).use { reader ->
                    PrintStream(respOut, true, StandardCharsets.UTF_8).use { writer ->
                        BatchScenarioWorker.serve(reader, writer, sessionOnly = true)
                    }
                }
            }
            try {
                OutputStreamWriter(reqOut, StandardCharsets.UTF_8).use { sender ->
                    BufferedReader(InputStreamReader(respIn, StandardCharsets.UTF_8)).use { receiver ->
                        sender.write(obj("id" to s("bad"), "cmd" to s("core-run")).toString() + "\n")
                        sender.flush()
                        val badReply = json.parseToJsonElement(assertNotNull(receiver.readLine())).jsonObject
                        assertFalse(badReply["ok"]!!.jsonPrimitive.boolean)
                        assertEquals(1, badReply["exitCode"]!!.jsonPrimitive.int)

                        val sessOut = tempRoot.resolve("good-sess")
                        val prodPlan = publicRoot.resolve("platform/scenarios/production.json").toAbsolutePath().normalize()
                        val prodInputs = prodPlan.parent.resolve(
                            json.parseToJsonElement(Files.readString(prodPlan)).jsonObject["inputs"]!!.jsonPrimitive.content
                        ).normalize()
                        val goodReq = obj(
                            "id" to s("good"),
                            "inputs" to s(prodInputs.toString()),
                            "plan" to s(prodPlan.toString()),
                            "output" to s(sessOut.toString()),
                        )
                        sender.write(goodReq.toString() + "\n")
                        sender.flush()
                        val goodReply = json.parseToJsonElement(assertNotNull(receiver.readLine())).jsonObject
                        assertTrue(goodReply["ok"]!!.jsonPrimitive.boolean, goodReply.toString())
                        assertEquals(0, goodReply["exitCode"]!!.jsonPrimitive.int)
                        assertTrue(Files.isRegularFile(sessOut.resolve("client-diagnostic.jsonl")))
                    }
                }
                workerThread.join(10_000)
                assertFalse(workerThread.isAlive)
            } finally {
                System.setOut(savedOut)
                System.setErr(savedErr)
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
            tempRoot.toFile().deleteRecursively()
        }
    }
}
