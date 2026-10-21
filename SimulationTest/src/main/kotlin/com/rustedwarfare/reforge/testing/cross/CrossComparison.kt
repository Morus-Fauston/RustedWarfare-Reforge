package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

/** Versioned, exact tool-fixture adapter. No original-game compatibility contract is accepted. */
object CrossComparison {
    fun compare(contractPath: Path, actualPath: Path, expectedPath: Path): Judgment {
        val evidence = mutableListOf<JsonElement>()
        var contractId: String? = null
        fun result(code: Int, category: String, reason: String? = null, first: JsonElement = JsonNull,
                   evaluations: List<JsonElement> = emptyList()): Judgment = Judgment(code, obj(
            "kind" to s("cross-comparison"), "schemaVersion" to n(1), "contractId" to contractId?.let(::s),
            "result" to s(when (code) { 0 -> "completed"; 2 -> "diverged"; 3 -> "evidence-insufficient"; else -> "execution-failed" }),
            "exitCode" to n(code), "category" to s(category), "reason" to reason?.let(::s),
            "originalCompatibility" to b(false), "evidenceRefs" to array(evidence),
            "fieldEvaluations" to array(evaluations), "firstDivergence" to first))
        return try {
            val bytes = Files.readAllBytes(contractPath)
            evidence += identity(contractPath, bytes)
            val contract = strictObject(decode(bytes))
            if (contract["scope"] == s("original-motion-v1")) {
                return OriginalMotionComparison.compare(contractPath, actualPath, expectedPath)
            }
            contractId = contract.id("contractId")
            val spec = specification(contract)
            val actual = read(actualPath, "actual", contract, spec, evidence)
            val expected = read(expectedPath, "expected", contract, spec, evidence)
            val evaluations = mutableListOf<JsonElement>()
            for (index in 1 until maxOf(actual.size, expected.size) - 1) {
                val a = actual.getOrNull(index)?.takeUnless { it["kind"] == s("end") }
                val e = expected.getOrNull(index)?.takeUnless { it["kind"] == s("end") }
                val projectedA = a?.let { project(it, "actual", spec) }
                val projectedE = e?.let { project(it, "expected", spec) }
                val fields = (projectedE?.keys.orEmpty() + projectedA?.keys.orEmpty()).distinct()
                for (field in fields) {
                    val av = projectedA?.get(field) ?: JsonNull
                    val ev = projectedE?.get(field) ?: JsonNull
                    val equal = exact(av, ev)
                    evaluations += obj("line" to n(index + 1), "field" to s(field), "equal" to b(equal), "actual" to av, "expected" to ev)
                    if (!equal) return result(2, "field-difference", first = obj(
                        "field" to s(field), "actual" to av, "expected" to ev,
                        "actualLine" to (a?.let { n(index + 1) } ?: JsonNull), "expectedLine" to (e?.let { n(index + 1) } ?: JsonNull),
                        "rawActual" to a, "rawExpected" to e,
                        "actualContext" to context(actual, index), "expectedContext" to context(expected, index)), evaluations = evaluations)
                }
            }
            result(0, "exact-tool-only-cross", evaluations = evaluations)
        } catch (failure: CrossFailure) {
            result(failure.code, failure.category, failure.message)
        } catch (failure: InputError) {
            result(64, "input-error", failure.message)
        } catch (failure: IllegalArgumentException) {
            result(64, "input-error", failure.message)
        } catch (failure: java.io.IOException) {
            result(4, "io-failed", failure.message)
        }
    }

    private data class Field(val name: String, val actual: String, val expected: String, val unit: String)
    private data class Spec(val steps: List<Long>, val objects: List<JsonObject>, val samples: List<Field>, val inputs: List<Field>)

    private fun specification(c: JsonObject): Spec {
        c.header("cross-contract")
        keys(c, setOf("kind", "schemaVersion", "contractId", "scope", "approval", "exclusions", "tolerances", "actual", "expected", "units", "objects", "requiredSteps", "sampleFields", "inputFields"))
        keys(c.objectAt("approval"), setOf("status", "source"))
        if (c.text("scope") != "tool-only-cross" || c.objectAt("approval").text("status") != "tool-fixture-only" ||
            s("original-compatibility") !in c.arrayAt("exclusions") || c.objectAt("tolerances").isNotEmpty()) {
            throw InputError("Only tool-fixture-only exact cross comparison is supported")
        }
        c.objectAt("approval").text("source")
        for (side in listOf("actual", "expected")) {
            val source = c.objectAt(side)
            keys(source, setOf("implementationId", "traceSchema", "traceSha256"))
            source.id("implementationId")
            if (source.text("traceSchema") != "cross-trajectory-v1" || !Regex("[a-f0-9]{64}").matches(source.text("traceSha256"))) {
                throw InputError("Unsupported trace schema or identity")
            }
        }
        val units = c.objectAt("units")
        keys(units, setOf("actualTime", "expectedTime", "actualLength", "expectedLength", "actualAngle", "expectedAngle"))
        for (kind in listOf("Time", "Length", "Angle")) {
            if (units.text("actual$kind") != units.text("expected$kind")) throw InputError("Unit conversion is not supported: $kind")
        }
        if (units.text("actualTime") != "simulation-step") throw InputError("Only explicit simulation-step time is supported")
        if (units.text("actualLength") != "world-unit" || units.text("actualAngle") != "degree") throw InputError("Only world-unit length and degree angles are supported")
        val objects = c.arrayAt("objects").map { it.jsonObject }
        objects.forEach { keys(it, setOf("canonicalId", "actualId", "expectedId")) }
        for (key in listOf("canonicalId", "actualId", "expectedId")) {
            if (objects.isEmpty() || objects.map { it.id(key) }.distinct().size != objects.size) throw InputError("Empty or duplicate object mapping: $key")
        }
        val steps = c.arrayAt("requiredSteps").map { value ->
            value.jsonPrimitive.takeUnless { it.isString }?.longOrNull ?: throw InputError("Invalid required step")
        }
        if (steps.isEmpty() || steps.first() < 0 || steps.zipWithNext().any { (a, b) -> a >= b }) throw InputError("Required steps must be explicit, unique and increasing")
        fun fields(key: String): List<Field> {
            val fields = c.arrayAt(key).map { value ->
                val f = value.jsonObject
                keys(f, setOf("name", "actualPath", "expectedPath", "unit"))
                if (f.text("unit") !in setOf("length", "angle", "time", "identity")) throw InputError("Unknown field unit")
                fun path(name: String): String = f.text(name).also {
                    if (!Regex("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)*").matches(it)) throw InputError("Invalid field path")
                }
                val name = path("name")
                if (key == "sampleFields" && name == "id") throw InputError("Sample field name id is reserved for object identity")
                Field(name, path("actualPath"), path("expectedPath"), f.text("unit"))
            }
            if (fields.isEmpty() || fields.map { it.name }.distinct().size != fields.size ||
                fields.map { it.actual }.distinct().size != fields.size || fields.map { it.expected }.distinct().size != fields.size) {
                throw InputError("Empty or duplicate field mapping")
            }
            return fields
        }
        return Spec(steps, objects, fields("sampleFields"), fields("inputFields"))
    }

    private fun read(path: Path, side: String, c: JsonObject, spec: Spec, evidence: MutableList<JsonElement>): List<JsonObject> {
        val bytes = Files.readAllBytes(path)
        evidence += identity(path, bytes)
        val source = c.objectAt(side)
        if (digest(bytes) != source.text("traceSha256")) throw InputError("$side trace identity mismatch")
        fun incomplete(line: Int, message: String): Nothing = throw CrossFailure(3, "incomplete-trace", "$side line $line: $message")
        val text = try { decode(bytes) } catch (failure: IllegalArgumentException) { incomplete(1, failure.message ?: "Invalid UTF-8") }
        if (text.isEmpty() || !text.endsWith('\n')) incomplete(1, "Empty or truncated JSONL; final newline required")
        val rows = text.lineSequence().toList().dropLast(1).mapIndexed { index, line ->
            try {
                strictObject(line)
            } catch (failure: IllegalArgumentException) { incomplete(index + 1, "Malformed JSON object: ${failure.message}") }
        }
        rows.forEachIndexed { index, row ->
            if (row["status"] == s("execution-failed") || row["kind"] == s("crash") || row["exitCode"] == n(4)) {
                throw CrossFailure(4, "recorded-execution-failure", "$side line ${index + 1}: $row")
            }
        }
        try {
            if (rows.size < 3 || rows.first().text("kind") != "start" || rows.last().text("kind") != "end") incomplete(rows.size, "Missing start/end")
            if (rows.first().text("implementationId") != source.text("implementationId") || rows.first().text("traceSchema") != source.text("traceSchema")) {
                throw InputError("$side trace start identity mismatch")
            }
            val end = rows.last()
            if (end.required("complete") != b(true) || end.text("status") != "completed" || end.integer("exitCode") != 0L || end.integer("recordCount") != rows.size.toLong()) {
                incomplete(rows.size, "Invalid completion marker/count")
            }
            val ids = spec.objects.map { it.id("${side}Id") }
            var observed = 0
            for (index in 1 until rows.lastIndex) {
                val row = rows[index]
                try {
                    when (row.text("kind")) {
                        "sample" -> {
                            if (row.integer("step") != spec.steps.getOrNull(observed++)) incomplete(index + 1, "Missing, duplicate or out-of-order step")
                            val objects = row.arrayAt("objects").map { it.jsonObject }
                            val found = objects.map { it.id("id") }
                            if (found.size != ids.size || found.toSet() != ids.toSet()) incomplete(index + 1, "Missing, duplicate or unknown object")
                            objects.forEach { value -> spec.samples.forEach { field -> mapped(value, field, side) } }
                        }
                        "input" -> {
                            if (row.integer("step") !in spec.steps || row.id("objectId") !in ids) incomplete(index + 1, "Unknown input step/object")
                            if (observed == 0 || row.integer("step") != spec.steps[observed - 1]) incomplete(index + 1, "Input is not at the currently observed step boundary")
                            val payload = row.objectAt("payload")
                            spec.inputs.forEach { mapped(payload, it, side) }
                        }
                        else -> incomplete(index + 1, "Unexpected record kind")
                    }
                } catch (failure: InputError) { incomplete(index + 1, failure.message ?: "Missing field") }
            }
            if (observed != spec.steps.size) incomplete(rows.size, "Missing required sample step")
        } catch (failure: InputError) {
            if (failure.message?.contains("identity mismatch") == true) throw failure
            incomplete(rows.size, failure.message ?: "Missing field")
        }
        return rows
    }

    private fun lookup(value: JsonObject, path: String): JsonElement {
        var current: JsonElement = value
        for (part in path.split('.')) current = (current as? JsonObject)?.get(part) ?: throw InputError("Missing mapped field: $path")
        if (current == JsonNull) throw InputError("Null mapped field: $path")
        return current
    }

    private fun mapped(value: JsonObject, field: Field, side: String): JsonElement {
        val element = lookup(value, if (side == "actual") field.actual else field.expected)
        fun numericTree(item: JsonElement): Boolean = when (item) {
            is JsonObject -> item.isNotEmpty() && item.values.all(::numericTree)
            is JsonArray -> item.isNotEmpty() && item.all(::numericTree)
            is JsonPrimitive -> !item.isString && item.content.toBigDecimalOrNull() != null
        }
        if (field.unit == "identity") {
            if (element !is JsonPrimitive || !element.isString || element.content.isBlank()) throw InputError("Identity field requires a nonempty string: ${field.name}")
        } else if (!numericTree(element)) throw InputError("Dimensional field requires nonempty numeric leaves: ${field.name}")
        return element
    }

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("Invalid UTF-8 bytes") }

    private fun keys(value: JsonObject, allowed: Set<String>) {
        if (value.keys.any { it !in allowed }) throw InputError("Unsupported contract policy/field: ${value.keys - allowed}")
    }

    private fun validJson(value: JsonElement) {
        when (value) {
            is JsonObject -> value.values.forEach(::validJson)
            is JsonArray -> value.forEach(::validJson)
            is JsonPrimitive -> if (!value.isString && value != JsonNull && value.booleanOrNull == null &&
                !Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(value.content)) {
                throw IllegalArgumentException("Invalid JSON number/literal")
            }
        }
    }

    // The JSON tree parser keeps the last duplicate key; reject duplicates before using its tree.
    internal fun strictObject(text: String): JsonObject {
        val parsed = json.parseToJsonElement(text).jsonObject.also(::validJson)
        data class Frame(val names: MutableSet<String>?, var key: Boolean)
        val stack = mutableListOf<Frame>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{' -> stack += Frame(mutableSetOf(), true)
                '[' -> stack += Frame(null, false)
                '}', ']' -> stack.removeAt(stack.lastIndex)
                ',' -> stack.lastOrNull()?.let { if (it.names != null) it.key = true }
                '"' -> {
                    val start = index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index++
                        index++
                    }
                    val frame = stack.lastOrNull()
                    if (frame?.names != null && frame.key) {
                        val name = json.parseToJsonElement(text.substring(start, index + 1)).jsonPrimitive.content
                        require(frame.names.add(name)) { "Duplicate JSON key: $name" }
                        frame.key = false
                    }
                }
            }
            index++
        }
        return parsed
    }

    private fun project(row: JsonObject, side: String, spec: Spec): JsonObject {
        val fields = linkedMapOf<String, JsonElement>("kind" to row.required("kind"), "step" to row.required("step"))
        fun canonical(id: String) = spec.objects.single { it.text("${side}Id") == id }.required("canonicalId")
        if (row.text("kind") == "input") {
            fields["objectId"] = canonical(row.text("objectId"))
            for (f in spec.inputs) fields["input.${f.name}"] = lookup(row.objectAt("payload"), if (side == "actual") f.actual else f.expected)
        } else {
            row.arrayAt("objects").forEachIndexed { index, value ->
                val unit = value.jsonObject
                fields["objects.$index.id"] = canonical(unit.text("id"))
                for (f in spec.samples) fields["objects.$index.${f.name}"] = lookup(unit, if (side == "actual") f.actual else f.expected)
            }
        }
        return JsonObject(fields)
    }

    private fun exact(a: JsonElement, e: JsonElement): Boolean = when {
        a is JsonPrimitive && e is JsonPrimitive && !a.isString && !e.isString -> {
            val an = a.content.toBigDecimalOrNull(); val en = e.content.toBigDecimalOrNull()
            if (an != null && en != null) an.compareTo(en) == 0 else a == e
        }
        a is JsonObject && e is JsonObject -> a.keys == e.keys && a.all { (key, value) -> exact(value, e.getValue(key)) }
        a is JsonArray && e is JsonArray -> a.size == e.size && a.zip(e).all { (av, ev) -> exact(av, ev) }
        else -> a == e
    }
    private fun identity(path: Path, bytes: ByteArray) = obj("path" to s(path.toAbsolutePath().normalize().toString()), "sha256" to s(digest(bytes)))
    private fun context(rows: List<JsonObject>, index: Int) = array((maxOf(0, index - 1)..minOf(rows.lastIndex, index + 1)).map {
        obj("line" to n(it + 1), "raw" to rows[it])
    })
}

private class CrossFailure(val code: Int, val category: String, message: String) : RuntimeException(message)
