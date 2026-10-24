package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class TraceArchiveIntegrityTest {
    @Test fun streaming_archive_validation_preserves_integrity_and_first_failure_precedence() {
        val repo = Path.of(System.getProperty("repo.root"))
        val scenario = repo.resolve("SimulationTest/fixtures/record-chain/scenario.json")
        val root = Files.createTempDirectory("p004-trace-integrity-")
        val rule = obj("ruleId" to s("stop.cancels"), "when" to obj("event" to s("action-accepted"), "actionKind" to s("stop")),
            "assertions" to array(listOf(obj("path" to s("units.u1.moveTarget"), "operator" to s("absent-or-null")),
                obj("path" to s("events"), "operator" to s("contains"), "value" to s("movement-cancelled")))))
        val contract = root.resolve("contract.json")
        Files.writeString(contract, JsonObject(readObject(repo.resolve("SimulationTest/fixtures/record-chain/contract.json")) +
            ("rules" to array(listOf(rule)))).toString())
        val original = root.resolve("original")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, original, listOf("p004-integrity"))).exitCode)
        assertEquals(0, TraceFiles.compare(scenario, contract, original).exitCode)
        fun variant(name: String, code: Int, reason: String, refresh: Boolean = true, change: (Path) -> Unit) {
            val target = root.resolve(name)
            Files.walk(original).use { paths -> paths.forEach { path ->
                val dest = target.resolve(original.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(dest) else Files.copy(path, dest)
            } }
            change(target)
            if (refresh) {
                val manifest = readObject(target.resolve("manifest.json"))
                val traces = manifest.arrayAt("traceFiles").map { value ->
                    val row = value.jsonObject
                    JsonObject(row + ("sha256" to s(sha256(target.resolve(row.text("path"))))))
                }
                Files.writeString(target.resolve("manifest.json"), JsonObject(manifest + ("traceFiles" to array(traces))).toString())
            }
            val result = TraceFiles.compare(scenario, contract, target)
            assertEquals(code, result.exitCode, name)
            assertTrue(result.document.toString().contains(reason), "$name: ${result.document}")
        }
        fun normalized(directory: Path) = directory.resolve("reforge/normalized.jsonl")
        variant("missing-newline", 3, "Missing final JSONL newline") { directory ->
            val file = normalized(directory); val bytes = Files.readAllBytes(file)
            Files.write(file, bytes.copyOf(bytes.size - 1))
        }
        variant("length-before-normalization", 3, "Raw/normalized line count mismatch") { directory ->
            val file = normalized(directory); val lines = Files.readAllLines(file).toMutableList()
            lines[0] = JsonObject(readObject(original.resolve("manifest.json")) + ("bad" to b(true))).toString()
            Files.writeString(file, lines.dropLast(1).joinToString("\n", postfix="\n"))
        }
        variant("parse-before-normalization", 3, "truncated-or-malformed-jsonl") { directory ->
            val file = normalized(directory); val lines = Files.readAllLines(file).toMutableList()
            lines[0] = "{}"; lines[lines.lastIndex] = "bad-json"
            Files.writeString(file, lines.joinToString("\n", postfix="\n"))
        }
        variant("first-normalization-difference", 3, "raw record 1") { directory ->
            val file = normalized(directory); val lines = Files.readAllLines(file).toMutableList()
            lines[0] = "{}"; lines[1] = "{}"
            Files.writeString(file, lines.joinToString("\n", postfix="\n"))
        }
        variant("missing-end", 3, "Missing run-end") { directory ->
            for (name in listOf("raw", "normalized")) {
                val file = directory.resolve("reforge/$name.jsonl")
                Files.writeString(file, Files.readAllLines(file).dropLast(1).joinToString("\n", postfix="\n"))
            }
        }
        variant("invalid-utf8", 4, "archive-io-failed") { directory ->
            Files.write(normalized(directory), byteArrayOf(0x80.toByte(), 10))
        }
        variant("hash-before-content", 3, "Missing, truncated or changed trace", refresh=false) { directory ->
            Files.writeString(normalized(directory), "{}\n")
        }
    }
}
