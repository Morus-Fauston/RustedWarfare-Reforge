package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class CrossComparisonTest {
    private val directory = System.getenv("REFORGE_CROSS_EVIDENCE")?.let(Path::of) ?: Files.createTempDirectory("reforge-cross-")
    private fun trace(id: String, objectId: String, x: String = "1", inputStep: Int = 0): String = """
        {"kind":"start","implementationId":"$id","traceSchema":"cross-trajectory-v1"}
        {"kind":"sample","step":0,"objects":[{"id":"$objectId","x":0}]}
        {"kind":"input","step":$inputStep,"objectId":"$objectId","payload":{"command":"move","target":{"x":1,"y":0}}}
        {"kind":"sample","step":1,"objects":[{"id":"$objectId","x":$x}]}
        {"kind":"end","complete":true,"status":"completed","exitCode":0,"recordCount":5}
    """.trimIndent() + "\n"

    private fun fixture(actualText: String = trace("a", "a1"), expectedText: String = trace("e", "e1"),
                        mutate: (JsonObject) -> JsonObject = { it }): Triple<Path, Path, Path> {
        val actual = directory.resolve("actual-${System.nanoTime()}.jsonl").also { Files.writeString(it, actualText) }
        val expected = directory.resolve("expected-${System.nanoTime()}.jsonl").also { Files.writeString(it, expectedText) }
        val contract = obj(
            "kind" to s("cross-contract"), "schemaVersion" to n(1), "contractId" to s("tool.cross.v1"),
            "scope" to s("tool-only-cross"), "approval" to obj("status" to s("tool-fixture-only"), "source" to s("literal-fixture")),
            "exclusions" to array(listOf(s("original-compatibility"))), "tolerances" to obj(),
            "actual" to obj("implementationId" to s("a"), "traceSchema" to s("cross-trajectory-v1"), "traceSha256" to s(sha256(actual))),
            "expected" to obj("implementationId" to s("e"), "traceSchema" to s("cross-trajectory-v1"), "traceSha256" to s(sha256(expected))),
            "units" to obj("actualTime" to s("simulation-step"), "expectedTime" to s("simulation-step"),
                "actualLength" to s("world-unit"), "expectedLength" to s("world-unit"), "actualAngle" to s("degree"), "expectedAngle" to s("degree")),
            "objects" to array(listOf(obj("canonicalId" to s("u1"), "actualId" to s("a1"), "expectedId" to s("e1")))),
            "requiredSteps" to array(listOf(n(0), n(1))),
            "sampleFields" to array(listOf(obj("name" to s("position.x"), "actualPath" to s("x"), "expectedPath" to s("x"), "unit" to s("length")))),
            "inputFields" to array(listOf(obj("name" to s("command"), "actualPath" to s("command"), "expectedPath" to s("command"), "unit" to s("identity")),
                obj("name" to s("target"), "actualPath" to s("target"), "expectedPath" to s("target"), "unit" to s("length"))))
        )
        val path = directory.resolve("contract-${System.nanoTime()}.json").also { Files.writeString(it, mutate(contract).toString()) }
        return Triple(path, actual, expected)
    }
    private fun compare(f: Triple<Path, Path, Path>) = CrossComparison.compare(f.first, f.second, f.third)

    @Test fun `literal file traces map explicit identities and compare exact decimal numbers`() {
        val result = compare(fixture(actualText = trace("a", "a1", "1.0")))
        assertEquals(0, result.exitCode)
        assertEquals(JsonNull, result.document["firstDivergence"])
        assertEquals(b(false), result.document["originalCompatibility"])
    }

    @Test fun `displacement and input timing preserve raw first difference and neighboring inputs`() {
        val later = trace("a", "a1", inputStep = 1).lines().toMutableList().also { rows ->
            val input = rows.removeAt(2)
            rows.add(3, input)
        }.joinToString("\n")
        for ((text, field, line) in listOf(Triple(trace("a", "a1", "1.000000001"), "objects.0.position.x", 4L),
            Triple(later, "kind", 3L))) {
            val result = compare(fixture(actualText = text))
            assertEquals(2, result.exitCode)
            val first = result.document.objectAt("firstDivergence")
            assertEquals(field, first.text("field"))
            assertEquals(line, first.integer("actualLine"))
            assertTrue(first.arrayAt("actualContext").any { it.jsonObject.objectAt("raw")["payload"] != null })
        }
    }

    @Test fun `incomplete and crashed evidence never passes even with an earlier difference`() {
        val base = trace("a", "a1", "2")
        val variants = listOf(
            base.replace("\"x\":2", "\"unused\":2") to 3,
            base.replace("{\"id\":\"a1\",\"x\":2}", "") to 3,
            base.lines().filterNot { it.contains("\"step\":1,\"objects\"") }.joinToString("\n") to 3,
            base.substringBeforeLast("{\"kind\":\"end\"") to 3,
            base.trimEnd() to 3,
            base.replace("\"status\":\"completed\",\"exitCode\":0", "\"status\":\"execution-failed\",\"exitCode\":4") to 4,
            base.replace("\"x\":2", "\"x\":NaN") to 3,
            base.replace("\"x\":2", "\"x\":Infinity") to 3,
            base.replace("\"step\":1,\"objects\"", "\"step\":0,\"objects\"") to 3,
            base.replace("{\"id\":\"a1\",\"x\":2}", "{\"id\":\"a1\",\"x\":2},{\"id\":\"a1\",\"x\":2}") to 3,
            base.replace("\"kind\":\"input\"", "\"kind\":\"start\"") to 3)
        for ((text, code) in variants) {
            val result = compare(fixture(actualText = text))
            assertEquals(code, result.exitCode, text)
            assertEquals(JsonNull, result.document["firstDivergence"])
        }
    }

    @Test fun `bad identities unsupported scopes tolerances empty duplicate and null mappings are rejected`() {
        assertEquals(64, compare(fixture(actualText = trace("wrong", "a1"))).exitCode)
        for ((key, value) in listOf("scope" to s("original-compatibility"), "tolerances" to obj("x" to n(0.1)),
            "sampleFields" to array(emptyList()), "policy" to s("round-before-compare"))) {
            assertEquals(64, compare(fixture { JsonObject(it + (key to value)) }).exitCode, key)
        }
        assertEquals(3, compare(fixture(actualText = trace("a", "a1").replace("\"x\":1", "\"x\":null"))).exitCode)
        assertEquals(64, compare(fixture { c -> JsonObject(c + ("sampleFields" to array(List(2) { c.arrayAt("sampleFields")[0] }))) }).exitCode)
        val wrongHash = fixture { c ->
            JsonObject(c + ("actual" to JsonObject(c.objectAt("actual") + ("traceSha256" to s("0".repeat(64))))))
        }
        assertEquals(64, compare(wrongHash).exitCode)
    }

    @Test fun `identical invalid input boundaries duplicate JSON keys and unknown nested policy are refused`() {
        assertEquals(3, compare(fixture(trace("a", "a1", inputStep = 1), trace("e", "e1", inputStep = 1))).exitCode)
        assertEquals(3, compare(fixture(actualText = trace("a", "a1").replace("\"x\":1", "\"x\":99,\"x\":1"))).exitCode)
        for ((key, value) in listOf("actualLength" to s("arbitrary"), "actualAngle" to s("arbitrary"))) {
            val expectedKey = key.replace("actual", "expected")
            assertEquals(64, compare(fixture { c ->
                JsonObject(c + ("units" to JsonObject(c.objectAt("units") + mapOf(key to value, expectedKey to value))))
            }).exitCode)
        }
        assertEquals(64, compare(fixture { c ->
            val f = c.arrayAt("sampleFields")[0].jsonObject
            JsonObject(c + ("sampleFields" to array(listOf(JsonObject(f + ("policy" to s("round")))))))
        }).exitCode)
        assertEquals(64, compare(fixture { c ->
            val f = c.arrayAt("sampleFields")[0].jsonObject
            JsonObject(c + ("sampleFields" to array(listOf(JsonObject(f + ("name" to s("id")))))))
        }).exitCode)
    }

    @Test fun `malformed UTF8 is rejected even when both byte streams decode to the same replacement character`() {
        val f = fixture()
        for (path in listOf(f.second, f.third)) {
            val bytes = Files.readAllBytes(path)
            val index = bytes.toString(Charsets.UTF_8).indexOf("move")
            bytes[index] = 0xc3.toByte()
            bytes[index + 1] = 0x28
            Files.write(path, bytes)
        }
        val c = readObject(f.first)
        Files.writeString(f.first, JsonObject(c + mapOf(
            "actual" to JsonObject(c.objectAt("actual") + ("traceSha256" to s(sha256(f.second)))),
            "expected" to JsonObject(c.objectAt("expected") + ("traceSha256" to s(sha256(f.third)))))).toString())
        assertEquals(3, compare(f).exitCode)
    }

    @Test fun `dimensional fields require nonempty numeric leaves and identity requires a nonempty string`() {
        val variants = listOf(
            trace("a", "a1", "\"1\"") to trace("e", "e1", "\"1\""),
            trace("a", "a1").replace("\"command\":\"move\"", "\"command\":true") to
                trace("e", "e1").replace("\"command\":\"move\"", "\"command\":true"),
            trace("a", "a1").replace("\"target\":{\"x\":1,\"y\":0}", "\"target\":{}") to
                trace("e", "e1").replace("\"target\":{\"x\":1,\"y\":0}", "\"target\":{}"))
        for ((actual, expected) in variants) assertEquals(3, compare(fixture(actual, expected)).exitCode)
    }

    @Test fun `separate CLI reads files emits result and never overwrites output`() {
        val batch = System.nanoTime()
        fun output(name: String) = directory.resolve("$name-$batch.json")
        val classpath = listOf(CrossComparison::class.java, Json::class.java, kotlinx.serialization.KSerializer::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
        fun invoke(f: Triple<Path, Path, Path>, output: Path): Int {
            val log = directory.resolve("cli-${System.nanoTime()}.log")
            val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
            val command = listOf(Path.of(System.getProperty("java.home"), "bin", javaName).toString(), "-cp", classpath,
                "com.rustedwarfare.reforge.testing.cross.CrossMainKt", "--contract", f.first.toString(), "--actual", f.second.toString(),
                "--expected", f.third.toString(), "--output", output.toString())
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "CLI timed out: $log")
            Files.writeString(log.resolveSibling(log.fileName.toString() + ".command.json"), obj("argv" to array(command.map(::s)),
                "cwd" to s(Path.of("").toAbsolutePath().normalize().toString()), "exitCode" to n(process.exitValue())).toString())
            return process.exitValue()
        }
        val output = output("result")
        val f = fixture()
        assertEquals(0, invoke(f, output))
        val bytes = Files.readAllBytes(output)
        assertEquals(4, invoke(f, output))
        assertContentEquals(bytes, Files.readAllBytes(output))
        assertEquals(2, invoke(fixture(actualText = trace("a", "a1", "2")), output("different")))
        assertEquals(3, invoke(fixture(actualText = trace("a", "a1").trimEnd()), output("truncated")))
        assertEquals(4, invoke(fixture(actualText = trace("a", "a1").replace("\"status\":\"completed\",\"exitCode\":0", "\"status\":\"execution-failed\",\"exitCode\":4")), output("crash")))
        assertEquals(64, invoke(fixture { JsonObject(it + ("scope" to s("original-compatibility"))) }, output("rejected")))
    }
}
