package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

/** Explicit c_turret_t1 and its one land template. No global replacements or upgrade actions. */
internal object TurretContentReader {
    private val action = setOf("buildSpeed", "convertTo", "description", "descriptionAddUnitStats", "displayType",
        "iconExtraColor", "iconExtraImage", "price", "text")
    private val fields = mapOf(
        "core" to setOf("buildSpeed", "buildingSelectionOffset", "buildingToFootprintOffsetX", "buildingToFootprintOffsetY",
            "class", "constructionFootprint", "copyFrom", "displayLocaleKey", "displayRadius", "dont_load",
            "fogOfWarSightRange", "footprint", "isBio", "isBuilding", "mass", "maxHp", "name", "overrideAndReplace",
            "price", "radius", "techLevel"),
        "graphics" to setOf("image", "imageScale", "image_shadow", "image_turret", "image_wreak", "shadowOffsetX",
            "shadowOffsetY", "total_frames", "turretImageScale"),
        "attack" to setOf("canAttack", "canAttackFlyingUnits", "canAttackLandUnits", "canAttackUnderwaterUnits",
            "maxAttackRange", "shootDelay", "turretSize", "turretTurnSpeed"),
        "turret_1" to setOf("canShoot", "idleSweepAngle", "idleSweepDelay", "idleSweepSpeed", "projectile", "recoilOffset",
            "recoilOutTime", "recoilReturnTime", "shoot_flame", "shoot_light", "shoot_sound", "shoot_sound_vol",
            "shouldResetTurret", "turnSpeedAcceleration", "turnSpeedDeceleration", "x", "y"),
        "movement" to setOf("maxTurnSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed", "moveSpeed", "movementType", "turnAcceleration"),
        "ai" to setOf("buildPriority", "maxEachBase", "noneInBaseExtraPriority", "upgradedFrom"),
        "action_upgrade_gun" to action, "action_upgrade_artillery" to action,
        "action_upgrade_flame" to action, "action_upgrade_lightning" to action,
        "projectile_1" to setOf("directDamage", "frame", "life", "speed"),
    )
    private val retained = setOf("core.price", "core.buildSpeed", "core.mass", "core.techLevel", "core.isBio",
        "core.displayLocaleKey", "core.buildingSelectionOffset", "graphics.image_shadow", "graphics.shadowOffsetX",
        "graphics.shadowOffsetY", "turret_1.shoot_sound", "turret_1.shoot_sound_vol", "turret_1.shoot_flame",
        "turret_1.shoot_light", "projectile_1.frame", "movement.moveAccelerationSpeed",
        "movement.moveDecelerationSpeed", "movement.turnAcceleration")

    fun read(path: Path): UnitDefinition {
        ordinaryFile(path, path, "core", "name")
        val child = parse(path)
        if (child["core"]?.get("dont_load") == "true")
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "dont_load", reason = "A template is not a unit")
        fun identity(key: String, value: String) {
            val actual = child["core"]?.get(key)
                ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "core", key, reason = "Missing turret identity")
            if (actual != value) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key, actual, "Unsupported turret identity")
        }
        identity("name", "c_turret_t1")
        identity("overrideAndReplace", "turret")
        val parentName = child["core"]?.get("copyFrom")
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "core", "copyFrom", reason = "Missing land template")
        val parent = reference(path, parentName, "core", "copyFrom")
        if (parentName != "turret_common_land.ini")
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "copyFrom", parentName, "Only the fixed land template is supported")
        val common = parse(parent)
        if (common["core"]?.containsKey("copyFrom") == true)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, parent, "core", "copyFrom", reason = "Nested inheritance is not supported")
        if (common["core"]?.get("dont_load") != "true")
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, parent, "core", "dont_load", reason = "Expected a template")
        val merged = linkedMapOf<String, MutableMap<String, String>>()
        val origins = mutableMapOf<Pair<String, String>, Path>()
        for ((file, sections) in listOf(parent to common, path to child)) for ((section, values) in sections) {
            val allowed = fields[section]
                ?: reject(ContentErrorCode.UNSUPPORTED_FIELD, file, section, reason = "Unsupported turret section")
            for ((key, value) in values) {
                if (key !in allowed) reject(ContentErrorCode.UNSUPPORTED_FIELD, file, section, key, reason = "Unsupported turret field")
                merged.getOrPut(section) { linkedMapOf() }[key] = value
                origins[section to key] = file
            }
        }
        fun origin(s: String, k: String) = origins[s to k] ?: path
        fun required(s: String, k: String): String = merged[s]?.get(k)?.takeIf { it.isNotBlank() }
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, origin(s, k), s, k, reason = "Missing turret field")
        fun only(s: String, k: String, value: String, default: String? = null) {
            val actual = merged[s]?.get(k) ?: default ?: required(s, k)
            if (actual != value) reject(ContentErrorCode.UNSUPPORTED_FEATURE, origin(s, k), s, k, actual, "Only $value is supported")
        }
        fun number(s: String, k: String, positive: Boolean = false, signed: Boolean = false, default: Double? = null): Double {
            val value = merged[s]?.get(k)?.toDoubleOrNull() ?: if (merged[s]?.containsKey(k) == true) null else default
            if (value == null) {
                if (merged[s]?.containsKey(k) != true && default == null) required(s, k)
                reject(ContentErrorCode.MALFORMED_SYNTAX, origin(s, k), s, k, reason = "Expected a number")
            }
            if (!value.isFinite() || (!signed && value < 0.0) || (positive && value <= 0.0))
                reject(ContentErrorCode.MALFORMED_SYNTAX, origin(s, k), s, k, reason = "Invalid finite turret number")
            return value
        }
        fun integer(s: String, k: String, positive: Boolean = false, default: Int? = null): Int {
            val value = merged[s]?.get(k)?.toIntOrNull() ?: if (merged[s]?.containsKey(k) == true) null else default
            if (value == null || value < 0 || (positive && value == 0))
                reject(ContentErrorCode.MALFORMED_SYNTAX, origin(s, k), s, k, reason = "Expected nonnegative integer")
            return value
        }
        fun float(s: String, k: String, positive: Boolean = false): Float {
            val value = number(s, k, positive, signed = !positive).toFloat()
            if (!value.isFinite()) reject(ContentErrorCode.MALFORMED_SYNTAX, origin(s, k), s, k, reason = "Expected finite binary32")
            return value
        }
        fun shape(key: String): BuildingFootprint {
            val raw = required("core", key).split(',').map { it.trim().toIntOrNull() }
            if (raw.size != 4 || raw.any { it == null })
                reject(ContentErrorCode.MALFORMED_SYNTAX, origin("core", key), "core", key, reason = "Expected four footprint integers")
            val v = raw.map { checkNotNull(it) }
            if (v[0] > v[2] || v[1] > v[3] || v[0] > 0 || v[1] > 0 || v[2] < 0 || v[3] < 0)
                reject(ContentErrorCode.MALFORMED_SYNTAX, origin("core", key), "core", key, reason = "Footprint must be ordered and contain its anchor")
            return BuildingFootprint(v[0], v[1], v[2], v[3])
        }
        only("core", "class", "CustomUnitMetadata")
        only("core", "isBuilding", "true")
        only("core", "isBio", "false")
        only("movement", "movementType", "NONE")
        for (key in listOf("moveSpeed", "maxTurnSpeed")) {
            if (number("movement", key) != 0.0)
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, origin("movement", key), "movement", key, reason = "Fixed buildings cannot move")
        }
        for (key in listOf("moveAccelerationSpeed", "moveDecelerationSpeed", "turnAcceleration")) number("movement", key)
        for ((key, value) in mapOf("canAttack" to "true", "canAttackFlyingUnits" to "false", "canAttackLandUnits" to "true", "canAttackUnderwaterUnits" to "false"))
            only("attack", key, value)
        only("turret_1", "shouldResetTurret", "false")
        only("turret_1", "canShoot", "true", "true")
        only("turret_1", "projectile", "1", "1")
        val footprint = shape("footprint")
        if (shape("constructionFootprint") != footprint)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, origin("core", "constructionFootprint"), "core", "constructionFootprint", reason = "Different placement shapes are not supported")
        val hp = integer("core", "maxHp", true).toDouble()
        val radius = number("core", "radius", true)
        val acceleration = number("turret_1", "turnSpeedAcceleration", signed = true, default = -1.0)
        val configuredW = number("turret_1", "turnSpeedDeceleration", signed = true, default = -1.0)
        val deceleration = if (configuredW < 0.0) acceleration else configuredW
        if (deceleration != acceleration)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, origin("turret_1", "turnSpeedDeceleration"), "turret_1", "turnSpeedDeceleration", reason = "Asymmetric turret acceleration is not supported")
        val recoil = WeaponRecoil(number("turret_1", "recoilOffset", signed = true),
            number("turret_1", "recoilOutTime", true), number("turret_1", "recoilReturnTime", true))
        if (!(recoil.outTime + recoil.returnTime).isFinite())
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, "turret_1", "recoilReturnTime", reason = "Recoil duration overflows")
        val sweepDelay = float("turret_1", "idleSweepDelay", true)
        val idle = IdleSweepProfile(number("turret_1", "idleSweepAngle", true), sweepDelay,
            float("turret_1", "idleSweepSpeed", true), if (sweepDelay > 200f) 20 else if (sweepDelay > 50f) 5 else 1)
        fun image(key: String): Path {
            val file = origin("graphics", key)
            val result = reference(file, required("graphics", key), "graphics", key)
            try { validatePng(result) } catch (failure: Exception) {
                reject(ContentErrorCode.BAD_REFERENCE, file, "graphics", key, reason = "Invalid PNG: ${failure.message}")
            }
            return result
        }
        val body = image("image")
        val dead = image("image_wreak")
        val turret = image("image_turret")
        val weapon = CombatProfile(hp, number("attack", "maxAttackRange", true), number("attack", "shootDelay", true),
            number("attack", "turretSize"), number("attack", "turretTurnSpeed"), maxOf(0.0, acceleration),
            ProjectileProfile(integer("projectile_1", "directDamage").toDouble(), number("projectile_1", "life", true), number("projectile_1", "speed", true)),
            turret, dead, number("graphics", "turretImageScale", true, default = 1.0),
            TurretMount(number("turret_1", "x", signed = true), number("turret_1", "y", signed = true)), recoil, idle)
        val kept = merged.flatMap { (s, values) -> values.keys.map { "$s.$it" }.filter {
            it in retained || s == "ai" || s.startsWith("action_upgrade_") } }.toSet()
        return UnitDefinition("c_turret_t1", "CustomUnitMetadata", radius, number("core", "displayRadius", true, default = radius),
            integer("graphics", "total_frames", true), body, number("graphics", "imageScale", true, default = 1.0),
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), kept,
            integer("core", "fogOfWarSightRange", default = 15), weapon, DurabilityProfile(hp, dead),
            BuildingProfile(footprint, anchor = FootprintAnchor(float("core", "buildingToFootprintOffsetX"), float("core", "buildingToFootprintOffsetY"))))
    }

    private fun parse(path: Path): Map<String, Map<String, String>> {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var current: String? = null
        Files.readAllLines(path).forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            if (line.startsWith('[')) {
                if (!line.endsWith(']') || line.count { it == '[' } != 1 || line.count { it == ']' } != 1)
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, current, reason = "Invalid section at line ${index + 1}")
                val next = line.substring(1, line.length - 1).trim()
                if (sections.putIfAbsent(next, linkedMapOf()) != null)
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, next, reason = "Duplicate section")
                current = next
            } else {
                val colon = line.indexOf(':')
                val section = current
                if (colon <= 0 || section == null) reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, reason = "Invalid field at line ${index + 1}")
                val key = line.substring(0, colon).trim()
                if (sections.getValue(section).putIfAbsent(key, line.substring(colon + 1).trim()) != null)
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, key, reason = "Duplicate field")
            }
        }
        return sections
    }

    private fun ordinaryFile(file: Path, origin: Path, section: String, field: String) {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || !Files.isReadable(file) || file.toRealPath() != file || file.parent.toRealPath() != file.parent)
            reject(ContentErrorCode.BAD_REFERENCE, origin, section, field, reason = "Expected ordinary content file")
    }

    private fun reference(file: Path, value: String, section: String, field: String): Path {
        val name = try { Path.of(value) } catch (failure: java.nio.file.InvalidPathException) {
            reject(ContentErrorCode.BAD_REFERENCE, file, section, field, value, "Invalid reference path")
        }
        if (name.isAbsolute || name.any { it.toString() == ".." })
            reject(ContentErrorCode.BAD_REFERENCE, file, section, field, value, "Reference must stay inside its content directory")
        val result = file.parent.resolve(name).normalize()
        if (!result.startsWith(file.parent)) reject(ContentErrorCode.BAD_REFERENCE, file, section, field, value, "Reference outside content")
        ordinaryFile(result, file, section, field)
        return result
    }

    /** Validate the fixed assets' non-interlaced RGBA8 PNG stream without desktop image APIs. */
    private fun validatePng(path: Path) {
        require(Files.size(path) <= 16L * 1024 * 1024) { "PNG exceeds fixed-asset read bound" }
        val bytes = Files.readAllBytes(path)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readLong() == -8552249625308161526L) { "PNG signature" }
            var width = 0; var height = 0; var ended = false
            val compressed = ByteArrayOutputStream()
            while (input.available() > 0) {
                val length = input.readInt()
                require(length >= 0 && length <= input.available() - 8) { "PNG chunk length" }
                val type = ByteArray(4); input.readFully(type)
                val data = ByteArray(length); input.readFully(data)
                val crc = input.readInt().toLong() and 0xffffffffL
                val actual = CRC32().apply { update(type); update(data) }.value
                require(crc == actual) { "PNG CRC" }
                val name = String(type, Charsets.US_ASCII)
                if (width == 0) {
                    require(name == "IHDR" && length == 13) { "PNG IHDR" }
                    DataInputStream(ByteArrayInputStream(data)).use { header ->
                        width = header.readInt(); height = header.readInt()
                        require(width > 0 && height > 0 && header.readUnsignedByte() == 8 && header.readUnsignedByte() == 6 &&
                            header.readUnsignedByte() == 0 && header.readUnsignedByte() == 0 && header.readUnsignedByte() == 0) { "Expected fixed RGBA8 PNG" }
                    }
                } else require(name != "IHDR") { "Duplicate PNG header" }
                when (name) {
                    "IDAT" -> compressed.write(data)
                    "IEND" -> { require(length == 0 && input.available() == 0); ended = true }
                }
                if (ended) break
            }
            val rowBytes = width.toLong() * 4L + 1L
            val expected = Math.multiplyExact(rowBytes, height.toLong())
            require(ended && compressed.size() > 0 && expected <= 16L * 1024 * 1024) { "Invalid fixed PNG size/end" }
            InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).use { pixels ->
                var count = 0L; val buffer = ByteArray(4096)
                while (true) {
                    val size = pixels.read(buffer)
                    if (size < 0) break
                    for (index in 0 until size) if ((count + index) % rowBytes == 0L)
                        require((buffer[index].toInt() and 255) <= 4) { "PNG row filter" }
                    count += size
                    require(count <= expected) { "PNG data overrun" }
                }
                require(count == expected) { "PNG data truncated" }
            }
        }
    }
}
