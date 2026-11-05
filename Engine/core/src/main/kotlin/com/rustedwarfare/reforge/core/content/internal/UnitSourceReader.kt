package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.InvalidPathException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.concurrent.CancellationException
import org.hjson.JsonObject
import org.hjson.JsonValue
import org.hjson.ParseException

/** Input schema only; unit semantics remain in the finite definition consumers. */
internal data class UnitContentSchema(
    val fields: Map<String, Set<String>>,
    val numericFields: Set<String>,
    val booleanFields: Set<String>,
    val textFields: Set<String>,
    val nativeFormats: Set<String> = emptySet(),
    val dynamicWeapons: Boolean = true,
    val timeFields: Set<String> = emptySet(),
    val timeFieldLocations: Set<Pair<String, String>> = emptySet(),
    val trimSectionName: Boolean = true,
) {
    companion object {
        fun native(fields: Set<String>, formats: Set<String>): UnitContentSchema {
            val numbers = fields.intersect(setOf("maxHp", "radius", "fogOfWarSightRange", "price", "buildSpeed", "incomePeriod", "incomeCredits"))
            return UnitContentSchema(mapOf("core" to fields), numbers, setOf("dont_load"), fields - numbers,
                nativeFormats = formats)
        }
    }
}

/** Shared syntax, inheritance and actual-byte provenance; no format-specific simulation. */
internal class UnitSourceReader(
    private val schema: UnitContentSchema,
    private val preRead: (Path) -> Unit = {},
    private val inputGuard: (Path, Input, Boolean) -> Unit = { _, _, _ -> },
) {
    private val fields = schema.fields
    private val numericFields = schema.numericFields
    private val booleanFields = schema.booleanFields
    private val textFields = schema.textFields

    fun load(path: Path, build: (EffectiveSections) -> UnitDefinition): LoadedUnitContent {
        val sources = linkedMapOf<Path, ContentSourceIdentity>()
        fun text(source: Path): String {
            if (Thread.currentThread().isInterrupted) throw CancellationException("Content load cancelled")
            val bytes = Files.readAllBytes(source)
            val identity = contentSourceIdentity(source, bytes)
            val previous = sources.putIfAbsent(identity.path, identity)
            if (previous != null && previous != identity)
                reject(ContentErrorCode.BAD_REFERENCE, source, reason = "Content source bytes changed during loading")
            return try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
            catch (failure: CharacterCodingException) {
                reject(ContentErrorCode.UNSUPPORTED_ENCODING, source, reason = "Expected UTF-8 content")
            }
        }
        val loaded = loadSections(path, ::text)
        val definition = try { build(loaded) } catch (failure: ContentReadException) {
            val error = failure.error
            val key = error.field
            val section = error.section ?: "core"
            val original = key?.let { loaded.origins[section to it]
                ?: if (schema.nativeFormats.isNotEmpty()) loaded.origins["core" to it] else null }
            if (original == null || error.file !in setOf(path.toString(), original.path.toString())) throw failure
            val context = if ((original.chain.size > 1 || original.section != error.section) && "inheritance=" !in error.reason)
                "; inheritance=${original.chain.joinToString(" -> ")}; consumer=${if (schema.nativeFormats.isNotEmpty()) key else "[$section].$key"}" else ""
            throw ContentReadException(error.copy(file = original.path.toString(), section = original.section,
                field = original.field, reason = error.reason + context))
        }
        val result = LoadedUnitContent(definition, sources.values.toList())
        when (val stable = result.verifyStable()) {
            is ContentResult.Failure -> throw ContentReadException(stable.error)
            is ContentResult.Success -> Unit
        }
        return result
    }

    private fun readNative(path: Path, text: String): Input {
        val values = linkedMapOf<String, String>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val separator = line.indexOf('=')
            if (separator <= 0) reject(ContentErrorCode.MALFORMED_SYNTAX, path,
                reason = "Expected native field=value at line ${index + 1}")
            val key = line.substring(0, separator).trim()
            if (key !in schema.fields.getValue("core") && key !in setOf("copyFrom", "dont_load"))
                reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = key, reason = "Unsupported native field")
            if (values.putIfAbsent(key, line.substring(separator + 1).trim()) != null)
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = key, reason = "Duplicate native field")
        }
        val controls = values.keys.intersect(setOf("copyFrom", "dont_load"))
        val parents = values.remove("copyFrom")?.split(',')?.map(String::trim).orEmpty()
        val sections = linkedMapOf("core" to values)
        return Input(sections, parents, templateOnly(path, sections), flat = true, declaredControls = controls)
    }

    private fun sectionKind(section: String): String = when {
        schema.dynamicWeapons && section.startsWith("turret_") -> "turret_1"
        schema.dynamicWeapons && section.startsWith("projectile_") -> "projectile_1"
        else -> section
    }

    private fun validName(name: String): Boolean = name.isNotBlank() && name == name.trim() &&
        name.none { it.isISOControl() || it in ",[]" }

    private fun allowedFields(section: String): Set<String>? =
        if (section.startsWith("template_")) fields.values.flatten().toSet() else fields[sectionKind(section)]

    internal data class Input(
        val sections: Map<String, MutableMap<String, String>>,
        val parents: List<String> = emptyList(),
        val templateOnly: Boolean = false,
        val structured: Boolean = false,
        val flat: Boolean = false,
        val declaredControls: Set<String> = emptySet(),
    )

    internal data class Origin(val path: Path, val section: String?, val field: String, val chain: List<Path> = listOf(path))
    private fun origin(path: Path, input: Input, section: String, key: String, chain: List<Path> = listOf(path)): Origin {
        val field = if (input.structured) when (key) {
            "@copyFromSection" -> "copyFromSections"
            "@copyFrom_skipThisSection" -> "skipInheritance"
            else -> key
        } else key
        return Origin(path, section.takeUnless { input.flat && it == "core" }, field, chain)
    }
    internal data class EffectiveSections(
        val values: MutableMap<String, MutableMap<String, String>>,
        val origins: MutableMap<Pair<String, String>, Origin>,
    )

    private fun templateOnly(path: Path, sections: Map<String, MutableMap<String, String>>): Boolean {
        val flag = sections["core"]?.remove("dont_load") ?: return false
        return when (flag) {
            "true" -> true
            "false" -> false
            else -> reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core".takeUnless { schema.nativeFormats.isNotEmpty() }, "dont_load", flag, "Expected a boolean template flag")
        }
    }

    private fun readDocument(path: Path, text: (Path) -> String): Input {
        preRead(path)
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        val extension = path.fileName.toString().substringAfterLast('.', "")
        if (extension.equals("json", true) || extension.equals("hjson", true)) {
            return readStructured(path, extension.equals("hjson", true), text(path))
        } else if (schema.nativeFormats.isNotEmpty()) {
            return readNative(path, text(path))
        } else {
            var section: String? = null
            text(path).lineSequence().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
                if (line.startsWith('[')) {
                    if (!line.endsWith(']') || line.count { it == '[' } != 1 || line.count { it == ']' } != 1) {
                        reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, reason = "Invalid section at line ${index + 1}")
                    }
                    val rawName = line.substring(1, line.length - 1)
                    val next = if (schema.trimSectionName) rawName.trim() else rawName
                    if (allowedFields(next) == null) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, next, reason = "Unsupported unit section")
                    if ((next.startsWith("turret_") || next.startsWith("projectile_") || next.startsWith("template_")) && !validName(next.substringAfter('_')))
                        reject(ContentErrorCode.IDENTITY_CONFLICT, path, next, "name", reason = "Invalid stable name")
                    if (sections.putIfAbsent(next, linkedMapOf()) != null) {
                        reject(ContentErrorCode.MALFORMED_SYNTAX, path, next, reason = "Duplicate section at line ${index + 1}")
                    }
                    section = next
                } else {
                    val colon = line.indexOf(':')
                    val current = section
                    if (colon <= 0 || current == null) {
                        reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, reason = "Invalid field at line ${index + 1}")
                    }
                    val key = line.substring(0, colon).trim()
                    if (key !in allowedFields(current).orEmpty() && key !in setOf("@copyFromSection", "@copyFrom_skipThisSection") && !(current == "core" && key in setOf("copyFrom", "dont_load"))) {
                        reject(ContentErrorCode.UNSUPPORTED_FIELD, path, current, key, reason = "Unsupported unit field")
                    }
                    val value = line.substring(colon + 1).trim()
                    if (key == "@copyFrom_skipThisSection" && value !in setOf("true", "false"))
                        reject(ContentErrorCode.MALFORMED_SYNTAX, path, current, key, value, "Expected a boolean inheritance flag")
                    if (sections.getValue(current).putIfAbsent(key, value) != null) {
                        reject(ContentErrorCode.MALFORMED_SYNTAX, path, current, key, reason = "Duplicate field at line ${index + 1}")
                    }
                }
            }
        }
        val controls = sections["core"]?.keys.orEmpty().intersect(setOf("copyFrom", "dont_load"))
        val parents = sections["core"]?.remove("copyFrom")?.split(',')?.map(String::trim).orEmpty()
        return Input(sections, parents, templateOnly(path, sections), declaredControls = controls)
    }

    private fun loadSections(path: Path, text: (Path) -> String): EffectiveSections {
        val root = readDocument(path, text)
        inputGuard(path, root, true)
        if (root.templateOnly) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core".takeUnless { root.flat }, "dont_load", reason = "Template-only input cannot be loaded as a unit")
        val target = root.sections.mapValuesTo(linkedMapOf()) { (_, values) -> values.toMutableMap() }
        val origins = linkedMapOf<Pair<String, String>, Origin>()
        for ((section, values) in target) for (key in values.keys) origins[section to key] = origin(path, root, section, key)
        fun inherit(source: Path, input: Input, depth: Int, active: Set<Path>, chain: List<Path>) {
            fun referenceFailure(name: String?, reason: String, code: ContentErrorCode = ContentErrorCode.BAD_REFERENCE): Nothing =
                reject(code, source, if (input.structured || input.flat) null else "core", "copyFrom", name,
                    "$reason; inheritance=${chain.joinToString(" -> ")}")
            if (depth > 10) referenceFailure(null, "Inheritance depth exceeds 10")
            for (name in input.parents.asReversed()) {
                if (name.isBlank() || name.contains("..") || name.startsWith("ROOT:") || name.startsWith("CORE:"))
                    referenceFailure(name, "Unsupported parent path", ContentErrorCode.UNSUPPORTED_FEATURE)
                val parent = try { source.parent.resolve(name).toAbsolutePath().normalize() }
                    catch (failure: InvalidPathException) { referenceFailure(name, "Invalid parent path") }
                val actual = try { parent.toRealPath() }
                    catch (failure: IOException) { referenceFailure(name, "Cannot resolve parent $parent: ${failure.message}") }
                    catch (failure: SecurityException) { referenceFailure(name, "Parent access denied: $parent") }
                if (actual in active) referenceFailure(name, "Cyclic inheritance: ${active.joinToString(" -> ")} -> $actual")
                val document = try { readDocument(parent, text).also { inputGuard(parent, it, false) } }
                    catch (failure: IOException) { referenceFailure(name, "Cannot read parent $parent: ${failure.message}") }
                    catch (failure: SecurityException) { referenceFailure(name, "Parent access denied: $parent") }
                    catch (failure: ContentReadException) {
                        val error = failure.error
                        val context = if ("inheritance=" in error.reason) "" else
                            "; inheritance=${(chain + listOf(parent)).joinToString(" -> ")}; consumer=$path"
                        throw ContentReadException(error.copy(reason = error.reason + context))
                    }
                for ((section, values) in document.sections) {
                    val effective = target.getOrPut(section) { linkedMapOf() }
                    if (effective["@copyFrom_skipThisSection"] == "true") continue
                    for ((key, value) in values) {
                        if (effective.putIfAbsent(key, value) == null) origins[section to key] = origin(parent, document, section, key, chain + listOf(parent))
                    }
                }
                inherit(parent, document, depth + 1, active + setOf(actual), chain + listOf(parent))
            }
        }
        inherit(path, root, 0, setOf(path.toRealPath()), listOf(path))
        val declared = target.mapValues { (_, values) -> values.toMap() }
        fun sectionFailure(section: String, ref: String? = null, reason: String): Nothing {
            val origin = origins[section to "@copyFromSection"] ?: Origin(path, section, "@copyFromSection")
            reject(ContentErrorCode.BAD_REFERENCE, origin.path, origin.section, origin.field, ref,
                "$reason; inheritance=${origin.chain.joinToString(" -> ")}; consumer=[$section]")
        }
        fun inheritSection(consumer: String, source: String, depth: Int, active: Set<String>) {
            if (depth > 10) sectionFailure(source, reason = "Section inheritance depth exceeds 10: ${active.joinToString(" -> ")}")
            val parents = declared.getValue(source)["@copyFromSection"]?.takeIf { it.isNotBlank() } ?: return
            for (name in parents.split(',').map(String::trim).asReversed()) {
                val values = declared[name]
                if (name.isBlank() || values.isNullOrEmpty()) sectionFailure(source, name, "Unknown or empty source section")
                if (name in active) sectionFailure(source, name, "Cyclic section inheritance: ${active.joinToString(" -> ")} -> $name")
                for ((key, value) in values) {
                    if (key in setOf("@copyFromSection", "@copyFrom_skipThisSection")) continue
                    if (target.getValue(consumer).putIfAbsent(key, value) == null)
                        origins[consumer to key] = origins.getValue(name to key)
                }
                inheritSection(consumer, name, depth + 1, active + name)
            }
        }
        for (section in declared.keys) inheritSection(section, section, 0, setOf(section))
        for ((section, values) in target) {
            values.remove("@copyFromSection")
            values.remove("@copyFrom_skipThisSection")
            for (key in values.keys) if (key !in allowedFields(section).orEmpty()) {
                val origin = origins.getValue(section to key)
                reject(ContentErrorCode.UNSUPPORTED_FIELD, origin.path, origin.section, origin.field, reason = "Field is unsupported in consuming section $section")
            }
        }
        return EffectiveSections(target, origins)
    }

    private fun readStructured(path: Path, hjson: Boolean, text: String): Input {
        fun malformed(section: String? = null, field: String? = null, reason: String): Nothing =
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, section.takeUnless { schema.nativeFormats.isNotEmpty() && it == "core" }, field, reason = reason)
        val parsed = try {
            if (hjson) JsonValue.readHjson(text) else JsonValue.readJSON(text)
        }
        catch (error: ParseException) {
            malformed(reason = "Invalid ${if (hjson) "Hjson" else "JSON"} at ${error.line}:${error.column}: ${error.message}")
        }
        if (!parsed.isObject) malformed(reason = "Expected a unit object")
        val root = parsed.asObject()
        val version = root.get("format")
        val versions = schema.nativeFormats.ifEmpty { setOf("rw-reforge.unit.v1") }
        if (version == null || !version.isString || version.asString() !in versions)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = "format", reason = "Expected format in $versions")
        val numericLexemes = try { StructuredNumberLexemes.read(text, parsed) }
        catch (failure: IllegalArgumentException) {
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, reason = failure.message ?: "Cannot retain numeric source tokens")
        }
        val result = linkedMapOf<String, MutableMap<String, String>>()
        var parents = emptyList<String>()
        fun fields(section: String, objectValue: JsonObject, named: Boolean = false): MutableMap<String, String> {
            if (allowedFields(section) == null)
                reject(ContentErrorCode.UNSUPPORTED_FIELD, path, section, reason = "Unsupported unit section")
            val values = linkedMapOf<String, String>()
            val seen = mutableSetOf<String>()
            for (member in objectValue) {
                val key = member.name
                if (!seen.add(key)) malformed(section, key, "Duplicate field")
                if (named && key == "name") continue
                if (key == "copyFromSections") {
                    if (!member.value.isArray) malformed(section, key, "Expected section name array")
                    values["@copyFromSection"] = member.value.asArray().map { value ->
                        if (!value.isString || !validName(value.asString())) malformed(section, key, "Expected a full section name")
                        value.asString()
                    }.joinToString(",")
                    continue
                }
                if (key == "skipInheritance") {
                    if (!member.value.isBoolean) malformed(section, key, "Expected a boolean inheritance flag")
                    values["@copyFrom_skipThisSection"] = member.value.toString()
                    continue
                }
                if (key !in allowedFields(section).orEmpty() && !(section == "core" && key == "dont_load"))
                    reject(ContentErrorCode.UNSUPPORTED_FIELD, path, section, key, reason = "Unsupported unit field")
                val value = member.value
                val correctType = when {
                    key in schema.timeFields || (section to key) in schema.timeFieldLocations ->
                        value.isNumber || value.isString && value.asString().endsWith('s')
                    key in numericFields -> value.isNumber
                    key in booleanFields -> value.isBoolean
                    key in textFields -> value.isString
                    else -> value.isString || value.isNumber || value.isBoolean
                }
                if (!correctType) malformed(section, key, "Wrong scalar type for $key")
                values[key] = when {
                    value.isString -> value.asString()
                    value.isNumber -> numericLexemes.getValue(value)
                    value.isBoolean -> value.toString()
                    else -> malformed(section, key, "Expected a scalar field")
                }
            }
            return values
        }
        if (schema.nativeFormats.isNotEmpty()) {
            val flat = JsonObject()
            val nativeSeen = mutableSetOf<String>()
            for (member in root) {
                val key = member.name
                if (!nativeSeen.add(key)) malformed(field = key, reason = "Duplicate member")
                if (key == "copyFrom") {
                    if (!member.value.isArray) malformed(field = key, reason = "Expected a parent filename array")
                    parents = member.value.asArray().map { value ->
                        if (!value.isString || value.asString().isBlank()) malformed(field = key, reason = "Expected a parent filename")
                        value.asString()
                    }
                } else {
                    if (key !in schema.fields.getValue("core") && key != "dont_load")
                        reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = key, reason = "Unsupported native field")
                    flat.add(key, member.value)
                }
            }
            result["core"] = fields("core", flat)
            return Input(result, parents, templateOnly(path, result), structured = true, flat = true,
                declaredControls = nativeSeen.intersect(setOf("copyFrom", "dont_load")))
        }
        val seen = mutableSetOf<String>()
        for (member in root) {
            val key = member.name
            if (!seen.add(key)) malformed(field = key, reason = "Duplicate member")
            when (key) {
                "format" -> Unit
                "copyFrom" -> {
                    if (!member.value.isArray) malformed(field = key, reason = "Expected a parent filename array")
                    parents = member.value.asArray().map { value ->
                        if (!value.isString || value.asString().isBlank()) malformed(field = key, reason = "Expected a parent filename")
                        value.asString()
                    }
                }
                in fields.keys.filter { !it.startsWith("turret_") && !it.startsWith("projectile_") && !it.startsWith("action_") } -> {
                    if (!member.value.isObject) malformed(key, reason = "Expected a field object")
                    result[key] = fields(key, member.value.asObject())
                }
                "weapons", "projectiles", "actions" -> {
                    if (key == "actions" && fields.keys.none { it.startsWith("action_") })
                        reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = key, reason = "Unsupported unit member")
                    if (!member.value.isArray) malformed(key, reason = "Expected a named array")
                    for (entry in member.value.asArray()) {
                        if (!entry.isObject) malformed(key, reason = "Expected a named object")
                        val value = entry.asObject()
                        val name = value.get("name")
                        if (name == null || !name.isString || !validName(name.asString()))
                            malformed(key, "name", "Expected a stable name")
                        val section = when (key) { "weapons" -> "turret_"; "projectiles" -> "projectile_"; else -> "action_" } + name.asString()
                        if (section in result)
                            reject(ContentErrorCode.IDENTITY_CONFLICT, path, section, "name", name.asString(), "Duplicate name")
                        result[section] = fields(section, value, true)
                    }
                }
                "templates" -> {
                    if (!member.value.isObject) malformed(key, reason = "Expected named template objects")
                    for (entry in member.value.asObject()) {
                        val section = entry.name
                        if (!section.startsWith("template_") || !validName(section.removePrefix("template_")))
                            malformed(section, reason = "Expected a full template_ section name")
                        if (!entry.value.isObject) malformed(section, reason = "Expected a template field object")
                        if (section in result)
                            reject(ContentErrorCode.IDENTITY_CONFLICT, path, section, reason = "Duplicate template name")
                        result[section] = fields(section, entry.value.asObject())
                    }
                }
                else -> reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = key, reason = "Unsupported unit member")
            }
        }
        val controls = result["core"]?.keys.orEmpty().intersect(setOf("copyFrom", "dont_load")) +
            seen.intersect(setOf("copyFrom"))
        return Input(result, parents, templateOnly(path, result), structured = true, declaredControls = controls)
    }
}
