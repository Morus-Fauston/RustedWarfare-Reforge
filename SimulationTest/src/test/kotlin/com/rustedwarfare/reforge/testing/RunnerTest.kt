package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*

class RunnerTest {
    private val repo = Path.of(System.getProperty("repo.root"))
    private val fixture = repo.resolve("SimulationTest/fixtures/record-chain/scenario.json")
    private val contract = fixture.resolveSibling("contract.json")

    @Test fun `real core records initial state commands steps and both trace layers`() {
        val output = Files.createTempDirectory("simulation-test-").resolve("run")
        val result = Runner.run(RunRequest(fixture, contract, output, listOf("test", "run")))
        assertEquals(3, result.exitCode) // No rule comparator in this ticket.
        val manifest = Json.parseToJsonElement(output.resolve("manifest.json").readText()).jsonObject
        assertEquals("collected", manifest["collectionStatus"]?.jsonPrimitive?.content)
        val raw = output.resolve("reforge/raw.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }
        val observations = raw.filter { it["kind"]?.jsonPrimitive?.content == "observation" }
        assertEquals(7, observations.size)
        val initial = observations.first()["observation"]!!.jsonObject
        assertEquals(0.0, initial["simulationTime"]!!.jsonPrimitive.double)
        val initialUnit = initial["units"]!!.jsonArray.first().jsonObject
        assertEquals(70.0, initialUnit["position"]!!.jsonObject["x"]!!.jsonPrimitive.double)
        val moved = observations[1]["observation"]!!.jsonObject["units"]!!.jsonArray.first().jsonObject
        assertTrue(moved["position"]!!.jsonObject["x"]!!.jsonPrimitive.double > 70.0)
        val results = raw.filter { it["kind"]?.jsonPrimitive?.content == "action-result" }
        assertEquals(listOf("select-1", "move-1", "foreign-stop", "stop-1"), results.map { it["result"]!!.jsonObject["actionId"]!!.jsonPrimitive.content })
        assertEquals("not-owner", results[2]["result"]!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertTrue(raw.any { it["kind"]?.jsonPrimitive?.content == "event" })
        val normalized = output.resolve("reforge/normalized.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(raw.size, normalized.size)
        assertTrue(normalized.all { it.containsKey("source") })
        assertTrue(raw.last()["complete"]!!.jsonPrimitive.boolean)
        assertEquals(6, raw.last()["observedSteps"]!!.jsonPrimitive.int)
    }

    @Test fun `unknown versions bad hashes missing dependencies and malformed numbers never initialize`() {
        val changes: List<(JsonObject) -> JsonObject> = listOf(
            { JsonObject(it + ("schemaVersion" to JsonPrimitive(2))) },
            { source -> JsonObject(source + ("content" to JsonObject(source["content"]!!.jsonObject.let { content ->
                content + ("map" to JsonObject(content["map"]!!.jsonObject + ("sha256" to JsonPrimitive("0".repeat(64)))))
            }))) },
            { source -> JsonObject(source + ("content" to JsonObject(source["content"]!!.jsonObject + ("dependencies" to JsonArray(emptyList()))))) },
            { source -> JsonObject(source - "timeModel") },
            { source -> JsonObject(source + ("units" to JsonArray(source["units"]!!.jsonArray.map {
                JsonObject(it.jsonObject + ("heading" to JsonPrimitive("NaN")))
            }))) },
        )
        changes.forEach { mutate ->
            val output = Files.createTempDirectory("simulation-invalid-").resolve("run")
            assertEquals(64, Runner.run(RunRequest(modified(mutate), contract, output, listOf("invalid-test"))).exitCode)
            val lines = output.resolve("reforge/raw.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }
            assertFalse(lines.any { it["kind"]?.jsonPrimitive?.content == "observation" })
            assertFalse(lines.last()["complete"]!!.jsonPrimitive.boolean)
            assertTrue(output.resolve("manifest.json").exists())
        }
    }

    @Test fun `repeat executions create independent evidence and retain identical core payloads`() {
        fun execute(): Path {
            val path = Files.createTempDirectory("simulation-repeat-").resolve("run")
            Runner.run(RunRequest(fixture, contract, path, listOf("repeat")))
            return path
        }
        val first = execute()
        val original = first.resolve("reforge/raw.jsonl").readText()
        val second = execute()
        fun samples(path: Path) = path.resolve("reforge/raw.jsonl").readLines().map {
            Json.parseToJsonElement(it).jsonObject
        }.filter { it["kind"]?.jsonPrimitive?.content == "observation" }.map { it["observation"] }
        assertEquals(samples(first), samples(second))
        assertEquals(original, first.resolve("reforge/raw.jsonl").readText())
        assertFailsWith<java.nio.file.FileAlreadyExistsException> {
            Runner.run(RunRequest(fixture, contract, first, listOf("overwrite")))
        }
        assertEquals(original, first.resolve("reforge/raw.jsonl").readText())
    }

    @Test fun `cancellation retains an honest partial trace and allows a fresh run`() {
        val output = Files.createTempDirectory("simulation-cancel-").resolve("run")
        var checks = 0
        val result = Runner.run(RunRequest(fixture, contract, output, listOf("cancel"), cancelled = { ++checks >= 5 }))
        assertEquals("cancelled", result.status)
        assertEquals(3, result.exitCode)
        val end = Json.parseToJsonElement(output.resolve("reforge/raw.jsonl").readLines().last()).jsonObject
        assertEquals(1, end["observedSteps"]!!.jsonPrimitive.int)
        assertFalse(end["complete"]!!.jsonPrimitive.boolean)
        val recovered = Files.createTempDirectory("simulation-recover-").resolve("run")
        assertEquals(3, Runner.run(RunRequest(fixture, contract, recovered, listOf("recover"))).exitCode)
        assertEquals("collected", readObject(recovered.resolve("manifest.json")).text("collectionStatus"))
    }

    @Test fun `timeout and core initialization rejection keep failure archives`() {
        val timeout = Files.createTempDirectory("simulation-timeout-").resolve("run")
        assertEquals(4, Runner.run(RunRequest(fixture, contract, timeout, listOf("timeout"), 1,
            { Thread.sleep(5); false })).exitCode)
        assertFalse(readObject(timeout.resolve("manifest.json"))["complete"]!!.jsonPrimitive.boolean)
        val badSpawn = modified { source -> JsonObject(source + ("units" to JsonArray(source["units"]!!.jsonArray.map {
            JsonObject(it.jsonObject + ("position" to obj("x" to n(-10), "y" to n(70))))
        }))) }
        val rejected = Files.createTempDirectory("simulation-core-rejected-").resolve("run")
        val result = Runner.run(RunRequest(badSpawn, contract, rejected, listOf("bad-spawn")))
        assertEquals(4, result.exitCode)
        assertTrue(readObject(rejected.resolve("manifest.json")).text("error").contains("invalid-spawn"))
    }

    @Test fun `numeric payloads and events survive normalization without rounding`() {
        val output = Files.createTempDirectory("simulation-normalized-").resolve("run")
        Runner.run(RunRequest(fixture, contract, output, listOf("normalized")))
        val raw = output.resolve("reforge/raw.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }
        val normalized = output.resolve("reforge/normalized.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }
        raw.zip(normalized).forEachIndexed { index, (original, standard) ->
            assertEquals(index + 1, standard["source"]!!.jsonObject["line"]!!.jsonPrimitive.int)
            if (original["kind"]?.jsonPrimitive?.content == "observation") {
                val expected = original["observation"]!!.jsonObject
                val actual = standard["observation"]!!.jsonObject
                assertEquals(expected["simulationTime"], actual["simulationTime"])
                assertEquals(expected["events"], actual["events"])
                expected["units"]!!.jsonArray.zip(actual["units"]!!.jsonArray).forEach { (a, b) ->
                    for (key in listOf("position", "heading", "speed", "angularVelocity", "moveTarget", "pathPoints")) {
                        assertEquals(a.jsonObject[key], b.jsonObject[key])
                    }
                }
            }
        }
    }

    @Test fun `invalid nested field types are input errors rather than core crashes`() {
        val badType = modified { source -> JsonObject(source + ("units" to JsonArray(listOf(JsonPrimitive("u1"))))) }
        val output = Files.createTempDirectory("simulation-bad-type-").resolve("run")
        assertEquals(64, Runner.run(RunRequest(badType, contract, output, listOf("bad-type"))).exitCode)
    }

    @Test fun `thread interruption can still finish the cancellation evidence`() {
        val output = Files.createTempDirectory("simulation-interrupt-").resolve("run")
        var result: RunResult? = null
        var failure: Throwable? = null
        val worker = Thread {
            try {
                result = Runner.run(RunRequest(fixture, contract, output, listOf("interrupt"), cancelled = {
                    Thread.currentThread().interrupt()
                    true
                }))
            } catch (error: Throwable) { failure = error }
        }
        worker.start()
        worker.join(15_000)
        assertFalse(worker.isAlive)
        assertNull(failure)
        assertEquals("cancelled", result?.status)
        assertTrue(output.resolve("manifest.json").exists())
    }

    private fun modified(mutate: (JsonObject) -> JsonObject): Path {
        val original = readObject(fixture)
        val content = original.objectAt("content")
        fun absolute(value: JsonObject) = JsonObject(value + ("path" to s(fixture.parent.resolve(value.text("path")).normalize().toString())))
        val rebound = JsonObject(original + ("content" to JsonObject(content + mapOf(
            "map" to absolute(content.objectAt("map")), "unitVariant" to absolute(content.objectAt("unitVariant")),
            "dependencies" to array(content.arrayAt("dependencies").map { absolute(it.jsonObject) }),
        ))))
        return Files.createTempFile("scenario-", ".json").also { it.writeText(mutate(rebound).toString()) }
    }
}
