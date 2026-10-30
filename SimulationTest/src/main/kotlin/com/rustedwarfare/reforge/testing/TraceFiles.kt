package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.*

/** Read-only archive comparison. No result, baseline, or source archive is rewritten. */
object TraceFiles {
    fun compare(scenarioPath: Path, contractPath: Path, actual: Path, expected: Path? = null): Judgment {
        var contract: JsonObject? = null
        val judgment = try {
            if (!Files.isRegularFile(scenarioPath) || !Files.isRegularFile(contractPath)) throw InputError("Missing scenario/contract")
            val scenario = readObject(scenarioPath)
            contract = readObject(contractPath)
            TraceComparator.validateContract(scenario, contract)
            val actualRows = readArchive(actual, scenarioPath, contractPath, scenario)
            val expectedRows = expected?.let { readArchive(it, scenarioPath, contractPath, scenario) }
            TraceComparator.evaluate(scenario, contract, actualRows, expectedRows)
        } catch (failure: ArchiveFailure) {
            TraceComparator.judgment(failure.code, if (failure.code == 4) "execution-failed" else "evidence-insufficient",
                contract, failure.category, reason = failure.message)
        } catch (failure: InputError) {
            TraceComparator.judgment(64, "execution-failed", contract, "input-error", reason = failure.message)
        } catch (failure: IllegalArgumentException) {
            TraceComparator.judgment(64, "execution-failed", contract, "input-error", reason = failure.message)
        } catch (failure: java.io.IOException) {
            TraceComparator.judgment(4, "execution-failed", contract, "archive-io-failed", reason = failure.message)
        }
        return judgment.copy(document = JsonObject(judgment.document + ("evidenceRefs" to array(
            (listOf(actual) + listOfNotNull(expected)).flatMap { directory ->
                listOf("manifest.json", "inputs/scenario.json", "inputs/contract.json", "reforge/raw.jsonl", "reforge/normalized.jsonl")
                    .map { s(directory.resolve(it).toAbsolutePath().normalize().toString()) }
            }))))
    }

    private fun readArchive(directory: Path, scenarioPath: Path, contractPath: Path, scenario: JsonObject): List<JsonObject> {
        fun missing(message: String): Nothing = throw ArchiveFailure(3, "archive-incomplete", message)
        val manifestPath = directory.resolve("manifest.json")
        if (!Files.isRegularFile(manifestPath)) missing("Missing manifest: $manifestPath")
        val manifest = try { readObject(manifestPath) } catch (failure: InputError) { missing("Malformed manifest: ${failure.message}") }
        if ("kind" !in manifest || "schemaVersion" !in manifest) missing("Missing manifest header")
        manifest.header("run-manifest")
        try {
            val status = manifest.text("status")
            val code = manifest.integer("exitCode")
            if (status == "execution-failed" && code != 64L || code == 4L) throw ArchiveFailure(4, "execution-failed", "Recorded execution failure: $directory")
            if (code == 64L) throw InputError("Archive records an input error")
            if (manifest.required("complete") != b(true) || status == "cancelled") missing("Manifest records incomplete collection")
            listOf("runId", "ticket", "startedAt", "finishedAt").forEach { manifest.text(it) }
            val start = Instant.parse(manifest.text("startedAt")); val finish = Instant.parse(manifest.text("finishedAt"))
            if (finish < start) missing("Manifest completion precedes start")
            if (manifest.objectAt("environment").isEmpty() || manifest.objectAt("coverage").isEmpty() || manifest.arrayAt("commands").isEmpty()) missing("Empty manifest evidence fields")
            fun identity(value: JsonObject) {
                value.id("id")
                if (!Regex("[a-fA-F0-9]{64}").matches(value.text("sha256"))) missing("Missing or invalid identity hash")
            }
            identity(manifest.objectAt("tool"))
            for ((field, path, id) in listOf(Triple("scenario", scenarioPath, scenario.id("scenarioId")),
                Triple("contract", contractPath, readObject(contractPath).id("contractId")))) {
                val declared = manifest.objectAt(field)
                identity(declared)
                if (declared.id("id") != id || declared.text("sha256") != sha256(path)) throw InputError("Archive $field identity mismatch")
                val snapshot = directory.resolve("inputs/$field.json")
                if (!Files.isRegularFile(snapshot)) missing("Missing input snapshot: $field")
                if (sha256(snapshot) != sha256(path)) throw InputError("Snapshot $field identity mismatch")
            }
            val inputs = manifest.arrayAt("inputs").map { it.jsonObject.also(::identity) }
            if (inputs.map { it.id("id") }.distinct().size != inputs.size) missing("Duplicate input identities")
            val content = scenario["content"] as? JsonObject
            if (content != null) {
                val contentRefs = listOf(content.objectAt("map")) + ScenarioDeclaration.unitReferences(content) +
                    content.arrayAt("dependencies").map { it.jsonObject }
                if (contentRefs.any { reference -> inputs.none { it["id"] == reference["id"] && it["sha256"] == reference["sha256"] } }) missing("Manifest lacks a required content identity")
            }
            val traces = manifest.arrayAt("traceFiles").map { it.jsonObject }
            if (traces.map { it.text("path") }.toSet() != setOf("reforge/raw.jsonl", "reforge/normalized.jsonl") || traces.size != 2) missing("Missing or duplicate trace identities")
            for (trace in traces) {
                val path = directory.resolve(trace.text("path"))
                if (!Files.isRegularFile(path) || sha256(path) != trace.text("sha256")) missing("Missing, truncated or changed trace: $path")
            }
            val raw = readLines(directory.resolve("reforge/raw.jsonl"))
            var normalizedCount = 0
            var normalizationFailure: RuntimeException? = null
            visitLines(directory.resolve("reforge/normalized.jsonl")) { index, standard ->
                normalizedCount++
                // Preserve the old precedence: parse all normalized rows, then check length,
                // then report the first normalization error. Never retain its full JSON tree.
                if (index < raw.size && normalizationFailure == null) {
                    try {
                        val canonical = normalizeRecord(raw[index], index + 1,
                            if (index == 0) null else scenario.objectAt("coordinateModel").id("id"),
                            if (index == 0) null else scenario.objectAt("timeModel").id("id"))
                        if (canonical != standard) missing("Normalization does not preserve raw record ${index + 1}")
                    } catch (failure: RuntimeException) { normalizationFailure = failure }
                }
            }
            if (raw.size != normalizedCount) missing("Raw/normalized line count mismatch")
            normalizationFailure?.let { throw it }
            val end = raw.lastOrNull() ?: missing("Empty trace")
            if (end["kind"] != s("run-end")) missing("Missing run-end")
            if (raw.first()["runId"] != manifest["runId"] || raw.first()["startedAt"] != manifest["startedAt"]) missing("Trace/manifest start mismatch")
            for (field in listOf("status", "exitCode", "complete")) if (manifest[field] != end[field]) missing("Manifest/run-end mismatch: $field")
            val coverage = manifest.objectAt("coverage")
            if (coverage["initialStateChecked"] != b(true)) missing("Initial state not checked")
            for (field in listOf("observedSteps", "requiredSteps")) if (coverage[field] != end[field]) missing("Coverage/run-end mismatch: $field")
            return raw
        } catch (failure: InputError) {
            if (failure.message?.contains("identity mismatch") == true || failure.message == "Archive records an input error") throw failure
            missing(failure.message ?: "Missing manifest/trace field")
        } catch (failure: IllegalArgumentException) {
            missing(failure.message ?: "Invalid manifest/trace field")
        }
    }

    private fun readLines(path: Path): List<JsonObject> = buildList {
        visitLines(path) { _, row -> add(row) }
    }

    private fun visitLines(path: Path, consume: (Int, JsonObject) -> Unit) {
        try {
            // Validate decoding and the final newline before parsing, as readString did.
            // A bounded character buffer replaces the former whole-file String and line list.
            var last = -1
            Files.newBufferedReader(path).use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (count > 0) last = buffer[count - 1].code
                }
            }
            if (last != '\n'.code) throw ArchiveFailure(3, "truncated-jsonl", "Missing final JSONL newline: $path")
            Files.newBufferedReader(path).use { reader ->
                var index = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    val row = json.parseToJsonElement(line) as? JsonObject
                        ?: throw IllegalArgumentException("Expected object at line ${index + 1}")
                    consume(index++, row)
                }
            }
        } catch (failure: IllegalArgumentException) {
            throw ArchiveFailure(3, "truncated-or-malformed-jsonl", "$path: ${failure.message}")
        }
    }

}

private class ArchiveFailure(val code: Int, val category: String, message: String) : RuntimeException(message)
