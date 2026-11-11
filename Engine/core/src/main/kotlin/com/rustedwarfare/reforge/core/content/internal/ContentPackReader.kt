package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.hjson.JsonObject
import org.hjson.JsonValue
import org.hjson.ParseException

/** Explicit entry discovery around the shared unit coordinator; no second unit parser. */
internal object ContentPackReader {
    private val manifestNames = listOf("pack.json", "pack.hjson")

    fun read(path: Path): LoadedContentPack {
        val manifest = if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
            val found = manifestNames.map(path::resolve).filter { Files.exists(it, NOFOLLOW_LINKS) }
            if (found.size != 1) reject(if (found.isEmpty()) ContentErrorCode.BAD_REFERENCE else ContentErrorCode.IDENTITY_CONFLICT,
                path, field = "pack", reason = "Expected exactly one of pack.json or pack.hjson; found ${found.size}")
            found.single()
        } else path
        if (manifest.fileName?.toString() !in manifestNames)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, manifest, field = "pack", reason = "Expected pack.json or pack.hjson")
        val root = manifest.parent
        requireOrdinary(root, manifest)
        val sources = linkedMapOf<Path, ContentSourceIdentity>()
        fun capture(file: Path, bytes: ByteArray) {
            val next = contentSourceIdentity(file, bytes)
            val previous = sources.putIfAbsent(next.path, next)
            if (previous != null && previous != next)
                reject(ContentErrorCode.BAD_REFERENCE, file, reason = "Package source changed during validation")
        }
        val bytes = Files.readAllBytes(manifest)
        capture(manifest, bytes)
        val text = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
        catch (failure: CharacterCodingException) {
            reject(ContentErrorCode.UNSUPPORTED_ENCODING, manifest, reason = "Expected UTF-8 package metadata")
        }
        val parsed = try {
            if (manifest.fileName.toString().endsWith(".hjson")) JsonValue.readHjson(text) else JsonValue.readJSON(text)
        } catch (failure: ParseException) {
            reject(ContentErrorCode.MALFORMED_SYNTAX, manifest,
                reason = "Invalid package metadata at ${failure.line}:${failure.column}: ${failure.message}")
        }
        val metadata = objectFields(parsed, setOf("format", "id", "name", "units", "dependencies", "replacements"), manifest)
        fun string(value: JsonValue?, field: String): String {
            if (value == null) reject(ContentErrorCode.MISSING_REQUIRED_FIELD, manifest, field = field, reason = "Required package field")
            if (!value.isString || value.asString().isBlank() || value.asString().any(Char::isISOControl))
                reject(ContentErrorCode.MALFORMED_SYNTAX, manifest, field = field, reason = "Expected nonblank text without control characters")
            return value.asString()
        }
        if (string(metadata.get("format"), "format") != "rw-reforge.content-pack.v1")
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, manifest, field = "format", reason = "Expected rw-reforge.content-pack.v1")
        val id = string(metadata.get("id"), "id")
        if (!id.matches(Regex("[a-z][a-z0-9_.-]*")))
            reject(ContentErrorCode.MALFORMED_SYNTAX, manifest, field = "id", reason = "Expected a stable lowercase package ID")
        val name = string(metadata.get("name"), "name")
        fun referenceId(value: JsonValue?, field: String): String {
            val result = string(value, field)
            if (!result.matches(Regex("[a-z][a-z0-9_.-]*")))
                reject(ContentErrorCode.MALFORMED_SYNTAX, manifest, field = field, ref = result, reason = "Expected a stable lowercase ID")
            return result
        }
        fun optionalArray(field: String): List<JsonValue> {
            val value = metadata.get(field) ?: return emptyList()
            if (!value.isArray) reject(ContentErrorCode.MALFORMED_SYNTAX, manifest, field = field, reason = "Expected an array")
            return value.asArray().toList()
        }
        val dependencies = mutableListOf<String>()
        for ((index, value) in optionalArray("dependencies").withIndex()) {
            val dependency = referenceId(value, "dependencies[$index]")
            if (dependency in dependencies) reject(ContentErrorCode.IDENTITY_CONFLICT, manifest, field = "dependencies[$index]",
                ref = dependency, reason = "Duplicate dependency")
            dependencies += dependency
        }
        val replacements = linkedMapOf<String, ContentReplacement>()
        for ((index, value) in optionalArray("replacements").withIndex()) {
            val field = "replacements[$index]"
            val entry = objectFields(value, setOf("contentId", "targetPackId", "targetContentId"), manifest, field)
            val contentId = referenceId(entry.get("contentId"), "$field.contentId")
            val targetPack = referenceId(entry.get("targetPackId"), "$field.targetPackId")
            val targetContent = referenceId(entry.get("targetContentId"), "$field.targetContentId")
            if (targetPack == id || targetPack !in dependencies)
                reject(ContentErrorCode.BAD_REFERENCE, manifest, field = "$field.targetPackId", ref = targetPack,
                    reason = "A replacement target must be a declared direct dependency other than this package")
            if (targetContent != contentId)
                reject(ContentErrorCode.BAD_REFERENCE, manifest, field = "$field.targetContentId", ref = targetContent,
                    reason = "Replacement must retain the runtime content ID")
            if (replacements.putIfAbsent(contentId, ContentReplacement(targetPack, targetContent)) != null)
                reject(ContentErrorCode.IDENTITY_CONFLICT, manifest, field = "$field.contentId", ref = contentId,
                    reason = "Duplicate replacement declaration")
        }
        val entries = metadata.get("units")
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, manifest, field = "units", reason = "Required package entries")
        if (!entries.isArray || entries.asArray().isEmpty)
            reject(ContentErrorCode.MALFORMED_SYNTAX, manifest, field = "units", reason = "Expected a nonempty entry array")
        val files = mutableSetOf<Path>()
        val units = linkedMapOf<String, UnitDefinition>()
        val unitFiles = linkedMapOf<String, Path>()
        for ((index, entry) in entries.asArray().withIndex()) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("Package validation cancelled")
            val field = "units[$index]"
            val spec = objectFields(entry, setOf("type", "file"), manifest, field)
            val capability = string(spec.get("type"), "$field.type")
            if (capability !in setOf("nuclear-silo", "experimental-factory", "nautilus", "mech-engineer", "repair-bay", "tank", "hover-tank", "hovercraft", "dropship", "aa-beam-gunship", "mech-bunker", "mech", "mech-heavy", "mech-factory", "builder", "constructor", "factory", "helicopter", "amphibious-jet", "air-factory", "battleship", "water-combat", "sea-factory", "underwater-target", "submarine", "command-center", "land-factory"))
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, manifest, field = "$field.type", reason = "Expected an implemented finite combat, mech, factory or builder capability")
            val reference = string(spec.get("file"), "$field.file")
            val pieces = reference.split('/')
            if (reference.any { it == '\\' || it == ':' } || pieces.any { it.isEmpty() || it == "." || it == ".." })
                reject(ContentErrorCode.BAD_REFERENCE, manifest, field = "$field.file", ref = reference, reason = "Expected a package-relative forward-slash path")
            val input = try { root.resolve(reference).toAbsolutePath().normalize() }
            catch (failure: InvalidPathException) {
                reject(ContentErrorCode.BAD_REFERENCE, manifest, field = "$field.file", ref = reference, reason = "Invalid package path")
            }
            requireOrdinary(root, input)
            if (input.fileName.toString().substringAfterLast('.') !in setOf("ini", "json", "hjson"))
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, manifest, field = "$field.file", ref = reference, reason = "Expected INI, JSON or Hjson unit content")
            if (!files.add(input)) reject(ContentErrorCode.IDENTITY_CONFLICT, manifest, field = "$field.file", ref = reference,
                reason = "Duplicate package entry")
            val loaded = when (capability) {
                "nuclear-silo" -> NuclearSiloContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "nautilus" -> NautilusContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "mech-engineer" -> MechEngineerContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "repair-bay" -> RepairBayContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "mech-bunker" -> BunkerContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "aa-beam-gunship" -> AaBeamContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "hovercraft" -> HovercraftContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "dropship" -> DropshipContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "amphibious-jet" -> AmphibiousJetContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "submarine" -> SubmarineContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "hover-tank" -> CombatContentReader.readHoverTankWithSources(input) { requireOrdinary(root, it) }
                "command-center" -> CommandCenterContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "land-factory" -> FactoryContentReader.readWithSources(input) { requireOrdinary(root, it) }.also {
                    if (it.definition.production?.exitProfile != FactoryExitProfile.NATIVE_LAND)
                        reject(ContentErrorCode.UNSUPPORTED_FEATURE, input, field = "format", reason = "Expected the finite native land factory profile")
                }
                "underwater-target" -> UnderwaterTargetContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "battleship" -> CombatContentReader.readBattleshipWithSources(input) { requireOrdinary(root, it) }
                "water-combat" -> CombatContentReader.readAuthorWaterWithSources(input) { requireOrdinary(root, it) }
                "sea-factory" -> FactoryContentReader.readWithSources(input) { requireOrdinary(root, it) }.also {
                    if (it.definition.production?.exitProfile != FactoryExitProfile.NATIVE_SEA)
                        reject(ContentErrorCode.UNSUPPORTED_FEATURE, input, field = "format", reason = "Expected the finite native sea factory profile")
                }
                "helicopter" -> CombatContentReader.readHelicopterWithSources(input) { requireOrdinary(root, it) }
                "air-factory" -> FactoryContentReader.readWithSources(input) { requireOrdinary(root, it) }.also {
                    if (it.definition.production?.exitProfile != FactoryExitProfile.NATIVE_AIR)
                        reject(ContentErrorCode.UNSUPPORTED_FEATURE, input, field = "format", reason = "Expected the finite native air factory profile")
                }
                "mech" -> CombatContentReader.readMechWithSources(input) { requireOrdinary(root, it) }
                "mech-heavy" -> CombatContentReader.readHeavyMechWithSources(input) { requireOrdinary(root, it) }
                "mech-factory" -> FactoryContentReader.readWithSources(input) { requireOrdinary(root, it) }.also {
                    if (it.definition.production?.exitProfile != FactoryExitProfile.CUSTOM_MECH)
                        reject(ContentErrorCode.UNSUPPORTED_FEATURE, input, field = "format", reason = "Expected the finite mech factory profile")
                }
                "experimental-factory" -> ExperimentalFactoryContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "builder" -> BuilderContentReader.readWithSources(input) { requireOrdinary(root, it) }
                "constructor" -> AuthorConstructionContentReader.readConstructorWithSources(input) { requireOrdinary(root, it) }
                "factory" -> AuthorConstructionContentReader.readBuildingWithSources(input) { requireOrdinary(root, it) }
                else -> CombatContentReader.readDeclaredWithSources(input) { requireOrdinary(root, it) }
            }
            val definition = loaded.definition
            if (units.putIfAbsent(definition.id, definition) != null)
                reject(ContentErrorCode.IDENTITY_CONFLICT, input, "core", "name", definition.id, "Duplicate logical content ID inside package $id")
            unitFiles[definition.id] = input
            for (source in loaded.sources) {
                val previous = sources.putIfAbsent(source.path, source)
                if (previous != null && previous != source)
                    reject(ContentErrorCode.BAD_REFERENCE, source.path, reason = "Package source changed between entries")
            }
            val combat = definition.combat
            val images = listOf("image" to definition.imagePath, "image_turret" to combat?.turretImagePath,
                "image_wreak" to (definition.durability?.deadImagePath ?: combat?.deadImagePath),
                "image_back" to definition.building?.backImagePath,
                "image_end" to definition.rotor?.imagePath, "image_shadow" to definition.shadowImagePath,
                "image_shield" to definition.shieldImagePath,
                "image_wing_left" to definition.amphibiousJet?.leftWingImagePath,
                "image_wing_right" to definition.amphibiousJet?.rightWingImagePath,
                "beam_image" to combat?.projectile?.beam?.image,
                "beam_image_start" to combat?.projectile?.beam?.startImage,
                "beam_image_end" to combat?.projectile?.beam?.endImage,
                "afterburn_trail_image" to definition.afterburnTrailImagePath,
                "image_arm1" to definition.nuclearSilo?.arm1ImagePath,
                "image_arm2" to definition.nuclearSilo?.arm2ImagePath) + definition.mech?.legs.orEmpty().flatMap {
                    listOf("image_leg" to it.legImage, "image_foot" to it.footImage) } +
                combat?.namedWeapons?.ordered.orEmpty().map { "turret_${it.name}.image" to it.imagePath }
            for ((slot, image) in images) {
                if (image == null) continue
                requireOrdinary(root, image)
                if (Files.size(image) > 16L * 1024 * 1024)
                    reject(ContentErrorCode.MALFORMED_SYNTAX, image, "graphics", slot, reason = "PNG exceeds existing read bound")
                capture(image, Files.readAllBytes(image))
                val dimensions = try { readPngMetadata(image, allowIndexed = true, allowGrayAlpha = true) }
                catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, image, "graphics", slot, reason = "Invalid package PNG: ${failure.message}")
                }
                if (slot == "image" && dimensions.width % definition.totalFrames != 0)
                    reject(ContentErrorCode.UNSUPPORTED_FEATURE, image, "graphics", "total_frames", reason = "Body frame count must divide the image width")
                capture(image, Files.readAllBytes(image))
            }
        }
        for (contentId in replacements.keys) if (contentId !in units)
            reject(ContentErrorCode.BAD_REFERENCE, manifest, field = "replacements", ref = contentId,
                reason = "Replacement content is not declared by this package")
        val loaded = LoadedContentPack(root, manifest, id, name, units, sources.values.toList(), dependencies, replacements, unitFiles)
        when (val stable = loaded.verifyStable()) {
            is ContentResult.Failure -> throw ContentReadException(stable.error)
            is ContentResult.Success -> Unit
        }
        return loaded
    }

    private fun objectFields(value: JsonValue, allowed: Set<String>, file: Path, field: String? = null): JsonObject {
        if (!value.isObject) reject(ContentErrorCode.MALFORMED_SYNTAX, file, field = field, reason = "Expected a metadata object")
        val result = value.asObject()
        val seen = mutableSetOf<String>()
        for (member in result) {
            val key = listOfNotNull(field, member.name).joinToString(".")
            if (!seen.add(member.name)) reject(ContentErrorCode.IDENTITY_CONFLICT, file, field = key, reason = "Duplicate metadata member")
            if (member.name !in allowed) reject(ContentErrorCode.UNSUPPORTED_FIELD, file, field = key, reason = "Unsupported package metadata member")
        }
        return result
    }

    internal fun requireOrdinary(root: Path, file: Path) {
        val normalized = file.toAbsolutePath().normalize()
        if (!normalized.startsWith(root) || !Files.isDirectory(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root) || root.toRealPath() != root)
            reject(ContentErrorCode.BAD_REFERENCE, file, reason = "Package path escapes an ordinary package root: $root")
        var segment = root
        for (part in root.relativize(normalized)) {
            segment = segment.resolve(part)
            if (Files.isSymbolicLink(segment) || segment.toRealPath() != segment)
                reject(ContentErrorCode.BAD_REFERENCE, file, reason = "Package path contains a link or redirected directory: $segment")
        }
        if (!Files.isRegularFile(normalized, NOFOLLOW_LINKS) || !Files.isReadable(normalized))
            reject(ContentErrorCode.BAD_REFERENCE, file, reason = "Expected a readable ordinary package file")
    }
}
