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

/** The three finite land turrets and their one local template. */
internal object TurretContentReader {
    private val actionFields = setOf("convertTo", "price", "text", "description", "descriptionAddUnitStats",
        "displayType", "buildSpeed", "iconExtraImage", "iconExtraColor")
    private val fields = mapOf(
        "core" to setOf("name", "class", "copyFrom", "dont_load", "overrideAndReplace", "displayLocaleKey",
            "price", "maxHp", "mass", "techLevel", "buildSpeed", "radius", "displayRadius", "fogOfWarSightRange",
            "isBio", "isBuilding", "footprint", "constructionFootprint", "buildingSelectionOffset",
            "buildingToFootprintOffsetX", "buildingToFootprintOffsetY", "fogOfWarSightRangeWhileNotBuilt"),
        "graphics" to setOf("total_frames", "image", "image_wreak", "image_turret", "imageScale", "turretImageScale",
            "image_shadow", "shadowOffsetX", "shadowOffsetY"),
        "attack" to setOf("canAttack", "canAttackFlyingUnits", "canAttackLandUnits", "canAttackUnderwaterUnits",
            "turretTurnSpeed", "turretSize", "maxAttackRange", "shootDelay"),
        "turret_1" to setOf("x", "y", "canShoot", "projectile", "shouldResetTurret", "idleSweepAngle", "idleSweepDelay",
            "idleSweepSpeed", "turnSpeedAcceleration", "turnSpeedDeceleration", "recoilOffset", "recoilOutTime",
            "recoilReturnTime", "shoot_sound", "shoot_sound_vol", "shoot_flame", "shoot_light", "turnSpeed",
            "barrelX", "barrelOffsetX_onOddShots"),
        "movement" to setOf("movementType", "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed", "maxTurnSpeed", "turnAcceleration"),
        "projectile_1" to setOf("directDamage", "life", "speed", "frame", "targetGround", "areaDamage", "areaRadius",
            "targetGroundSpread", "initialUnguidedSpeedHeight", "gravity", "drawType", "drawSize", "color",
            "largeHitEffect", "lightColor", "lightCastOnGround"),
        "ai" to setOf("upgradedFrom", "buildPriority", "noneInBaseExtraPriority", "maxEachBase"),
        "action_upgrade_gun" to actionFields, "action_upgrade_artillery" to actionFields,
        "action_upgrade_flame" to actionFields, "action_upgrade_lightning" to actionFields,
        "action_upgrade_gunT3" to actionFields, "action_upgrade_artilleryT2" to actionFields,
    )
    private val retained = setOf("core.price", "core.buildSpeed", "core.mass", "core.techLevel", "core.isBio",
        "core.displayLocaleKey", "core.buildingSelectionOffset", "graphics.image_shadow", "graphics.shadowOffsetX",
        "graphics.shadowOffsetY", "turret_1.shoot_sound", "turret_1.shoot_sound_vol", "turret_1.shoot_flame",
        "turret_1.shoot_light", "projectile_1.frame", "movement.moveAccelerationSpeed", "movement.moveDecelerationSpeed",
        "movement.turnAcceleration", "core.fogOfWarSightRangeWhileNotBuilt", "projectile_1.drawType",
        "projectile_1.drawSize", "projectile_1.color", "projectile_1.largeHitEffect", "projectile_1.lightColor",
        "projectile_1.lightCastOnGround")

    fun read(path: Path, enableUpgrades: Boolean = false): UnitDefinition {
        ordinary(path, path, "core", "name")
        val child = parse(path)
        fun childIdentity(key: String, expected: String) {
            val value = child["core"]?.get(key)
                ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "core", key, reason = "Missing turret identity")
            if (value != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key, value, "Unsupported turret identity")
        }
        if (child["core"]?.get("dont_load")?.let { it != "false" } == true)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "dont_load", reason = "A template cannot be loaded as a unit")
        val id = child["core"]?.get("name")
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "core", "name", reason = "Missing turret identity")
        val replacement = when (id) {
            "c_turret_t1" -> "turret"
            "c_turret_t2_gun" -> "turretT2"
            "c_turret_t1_artillery" -> "turret_artillery"
            else -> reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name", id, "Unsupported turret identity")
        }
        childIdentity("overrideAndReplace", replacement)
        if (enableUpgrades && id != "c_turret_t1")
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name", id, "Only the basic turret can enable first upgrades")
        val parentName = child["core"]?.get("copyFrom")
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "core", "copyFrom", reason = "Missing land template")
        val parentPath = reference(path, parentName, "core", "copyFrom")
        if (parentName != "turret_common_land.ini")
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "copyFrom", parentName, "Only the fixed local template is supported")
        val parent = parse(parentPath)
        if (parent["core"]?.containsKey("copyFrom") == true)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, parentPath, "core", "copyFrom", reason = "Nested inheritance is unsupported")
        if (parent["core"]?.get("dont_load") != "true")
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, parentPath, "core", "dont_load", reason = "Expected a template")
        val merged = linkedMapOf<String, MutableMap<String, String>>()
        val origins = mutableMapOf<Pair<String, String>, Path>()
        for ((file, sections) in listOf(parentPath to parent, path to child)) for ((s, values) in sections) {
            for ((k, v) in values) {
                merged.getOrPut(s) { linkedMapOf() }[k] = v
                origins[s to k] = file
            }
        }
        fun origin(s: String, k: String) = origins[s to k] ?: path
        fun required(s: String, k: String): String = merged[s]?.get(k)?.takeIf { it.isNotBlank() }
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, origin(s, k), s, k, reason = "Missing turret field")
        fun only(s: String, k: String, expected: String, default: String? = null) {
            val value = merged[s]?.get(k) ?: default ?: required(s, k)
            if (value != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, origin(s, k), s, k, value, "Only $expected is supported")
        }
        fun number(s: String, k: String, default: Double? = null, signed: Boolean = false, positive: Boolean = false): Double {
            val raw = merged[s]?.get(k)
            if (raw == null && default == null) required(s, k)
            val value = if (raw == null) default else raw.toDoubleOrNull()
            if (value == null || !value.isFinite() || (!signed && value < 0.0) || (positive && value <= 0.0))
                reject(ContentErrorCode.MALFORMED_SYNTAX, origin(s, k), s, k, raw, "Expected a finite number in range")
            return value
        }
        fun integer(s: String, k: String, default: Int? = null, positive: Boolean = false): Int {
            val raw = merged[s]?.get(k)
            if (raw == null && default == null) required(s, k)
            val value = if (raw == null) default else raw.toIntOrNull()
            if (value == null || value < 0 || (positive && value == 0))
                reject(ContentErrorCode.MALFORMED_SYNTAX, origin(s, k), s, k, raw, "Expected an integer in range")
            return value
        }
        fun binary32(s: String, k: String, positive: Boolean = false): Float {
            val value = number(s, k, signed = !positive, positive = positive).toFloat()
            if (!value.isFinite() || (positive && value <= 0f))
                reject(ContentErrorCode.MALFORMED_SYNTAX, origin(s, k), s, k, reason = "Expected a representable binary32 number")
            return value
        }
        fun time(s: String, k: String): Double {
            val raw = required(s, k)
            val seconds = raw.endsWith('s')
            val parsed = (if (seconds) raw.dropLast(1) else raw).toFloatOrNull()
            val ticks = parsed?.let { if (seconds) it * 60f else it }
            if (ticks == null || !ticks.isFinite() || ticks <= 0f)
                reject(ContentErrorCode.MALFORMED_SYNTAX, origin(s, k), s, k, raw, "Expected finite positive time")
            return ticks.toDouble()
        }
        fun footprint(k: String): BuildingFootprint {
            val values = required("core", k).split(',').map { it.trim().toIntOrNull() }
            if (values.size != 4 || values.any { it == null })
                reject(ContentErrorCode.MALFORMED_SYNTAX, origin("core", k), "core", k, reason = "Expected four footprint integers")
            val (l, t, r, b) = values.map { checkNotNull(it) }
            if (l > r || t > b || l > 0 || t > 0 || r < 0 || b < 0)
                reject(ContentErrorCode.MALFORMED_SYNTAX, origin("core", k), "core", k, reason = "Footprint must be ordered and contain its anchor")
            return BuildingFootprint(l, t, r, b)
        }
        only("core", "class", "CustomUnitMetadata"); only("core", "isBuilding", "true"); only("core", "isBio", "false")
        only("movement", "movementType", "NONE")
        for (k in listOf("moveSpeed", "maxTurnSpeed")) if (number("movement", k) != 0.0)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, origin("movement", k), "movement", k, reason = "Fixed turret cannot move")
        for (k in listOf("moveAccelerationSpeed", "moveDecelerationSpeed", "turnAcceleration")) number("movement", k)
        for ((k, v) in mapOf("canAttack" to "true", "canAttackFlyingUnits" to "false", "canAttackLandUnits" to "true", "canAttackUnderwaterUnits" to "false")) only("attack", k, v)
        only("turret_1", "canShoot", "true", "true"); only("turret_1", "projectile", "1", "1")
        only("turret_1", "shouldResetTurret", "false")
        val shape = footprint("footprint")
        if (footprint("constructionFootprint") != shape)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, origin("core", "constructionFootprint"), "core", "constructionFootprint", reason = "Different placement shapes are unsupported")
        val acceleration = number("turret_1", "turnSpeedAcceleration", -1.0, signed = true)
        val configuredDeceleration = number("turret_1", "turnSpeedDeceleration", -1.0, signed = true)
        if ((if (configuredDeceleration < 0.0) acceleration else configuredDeceleration) != acceleration)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, origin("turret_1", "turnSpeedDeceleration"), "turret_1", "turnSpeedDeceleration", reason = "Asymmetric turret acceleration is unsupported")
        val recoil = WeaponRecoil(number("turret_1", "recoilOffset", signed = true),
            number("turret_1", "recoilOutTime", positive = true), number("turret_1", "recoilReturnTime", positive = true))
        if (!(recoil.outTime + recoil.returnTime).isFinite())
            reject(ContentErrorCode.MALFORMED_SYNTAX, origin("turret_1", "recoilReturnTime"), "turret_1", "recoilReturnTime", reason = "Recoil duration overflow")
        val idle = if (id == "c_turret_t1_artillery" &&
            listOf("idleSweepAngle", "idleSweepDelay", "idleSweepSpeed").none { merged["turret_1"]?.containsKey(it) == true }) null
        else {
            val delay = binary32("turret_1", "idleSweepDelay", true)
            IdleSweepProfile(number("turret_1", "idleSweepAngle", positive = true), delay,
                binary32("turret_1", "idleSweepSpeed", true), if (delay > 200f) 20 else if (delay > 50f) 5 else 1)
        }
        val ground = if (id == "c_turret_t1_artillery") {
            only("projectile_1", "targetGround", "true")
            fun fixed(k: String, expected: Float): Float {
                val value = binary32("projectile_1", k, true)
                if (value != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, origin("projectile_1", k),
                    "projectile_1", k, reason = "Only the finite first artillery profile is supported")
                return value
            }
            GroundProjectileProfile(fixed("areaDamage", 80f), fixed("areaRadius", 55f), fixed("targetGroundSpread", 10f),
                fixed("initialUnguidedSpeedHeight", 1.2f), fixed("gravity", .18f))
        } else {
            if (listOf("targetGround", "areaDamage", "areaRadius", "targetGroundSpread", "initialUnguidedSpeedHeight", "gravity")
                .any { merged["projectile_1"]?.containsKey(it) == true })
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "projectile_1", reason = "Only artillery supports ground shots")
            null
        }
        fun image(k: String): Path {
            val file = origin("graphics", k)
            val result = reference(file, required("graphics", k), "graphics", k)
            try { validatePng(result) } catch (failure: Exception) {
                reject(ContentErrorCode.BAD_REFERENCE, file, "graphics", k, reason = "Invalid PNG: ${failure.message}")
            }
            return result
        }
        val body = image("image"); val dead = image("image_wreak"); val top = image("image_turret")
        val hp = integer("core", "maxHp", positive = true).toDouble()
        val radius = number("core", "radius", positive = true)
        val weapon = CombatProfile(hp, number("attack", "maxAttackRange", positive = true),
            if (ground != null) time("attack", "shootDelay") else number("attack", "shootDelay", positive = true),
            number("attack", "turretSize"), if (merged["turret_1"]?.containsKey("turnSpeed") == true) number("turret_1", "turnSpeed")
                else number("attack", "turretTurnSpeed"), maxOf(0.0, acceleration),
            ProjectileProfile(integer("projectile_1", "directDamage", if (ground != null) 0 else null).toDouble(),
                number("projectile_1", "life", positive = true), number("projectile_1", "speed", positive = true), ground = ground),
            top, dead, number("graphics", "turretImageScale", 1.0, positive = true),
            TurretMount(number("turret_1", "x", signed = true), number("turret_1", "y", signed = true)), recoil, idle,
            number("turret_1", "barrelX", 0.0, signed = true), number("turret_1", "barrelOffsetX_onOddShots", 0.0, signed = true))
        val consumed = mutableSetOf<String>()
        val upgrades = if (enableUpgrades) UpgradeProfile(listOf(
            "upgrade_gun" to "c_turret_t2_gun", "upgrade_artillery" to "c_turret_t1_artillery"
        ).map { (action, target) ->
            val section = "action_$action"
            only(section, "convertTo", target)
            val rawPrice = required(section, "price")
            val price = rawPrice.toLongOrNull()
            if (price == null || price < 0L) reject(ContentErrorCode.MALFORMED_SYNTAX, origin(section, "price"),
                section, "price", rawPrice, "Expected a nonnegative credit amount")
            val rate = binary32(section, "buildSpeed", true)
            listOf("convertTo", "price", "buildSpeed").forEach { consumed += "$section.$it" }
            UpgradeActionProfile(action, target, price, rate)
        }) else null
        val kept = merged.flatMap { (s, values) -> values.keys.map { "$s.$it" }.filter {
            (it in retained || s == "ai" || s.startsWith("action_upgrade_")) && it !in consumed } }.toSet()
        return UnitDefinition(id, "CustomUnitMetadata", radius,
            number("core", "displayRadius", radius, positive = true), integer("graphics", "total_frames", positive = true),
            body, number("graphics", "imageScale", 1.0, positive = true),
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), kept,
            integer("core", "fogOfWarSightRange", 15), weapon, DurabilityProfile(hp, dead),
            BuildingProfile(shape, anchor = FootprintAnchor(binary32("core", "buildingToFootprintOffsetX"), binary32("core", "buildingToFootprintOffsetY"))),
            upgrades = upgrades)
    }

    private fun parse(path: Path): Map<String, Map<String, String>> {
        val result = linkedMapOf<String, MutableMap<String, String>>()
        var section: String? = null
        Files.readAllLines(path).forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            if (line.startsWith('[')) {
                if (!line.endsWith(']') || line.count { it == '[' } != 1 || line.count { it == ']' } != 1)
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, reason = "Invalid section at line ${index + 1}")
                val name = line.substring(1, line.length - 1).trim()
                if (name !in fields) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, name, reason = "Unsupported turret section")
                if (result.putIfAbsent(name, linkedMapOf()) != null)
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, name, reason = "Duplicate section")
                section = name
            } else {
                val colon = line.indexOf(':'); val current = section
                if (current == null || colon <= 0) reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, reason = "Invalid field at line ${index + 1}")
                val key = line.substring(0, colon).trim()
                if (key !in fields.getValue(current)) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, current, key, reason = "Unsupported turret field")
                if (result.getValue(current).putIfAbsent(key, line.substring(colon + 1).trim()) != null)
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, current, key, reason = "Duplicate field")
            }
        }
        return result
    }

    private fun ordinary(file: Path, source: Path, section: String, field: String) {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || !Files.isReadable(file) ||
            file.toRealPath() != file || file.parent.toRealPath() != file.parent)
            reject(ContentErrorCode.BAD_REFERENCE, source, section, field, reason = "Expected ordinary content file")
    }

    private fun reference(source: Path, value: String, section: String, field: String): Path {
        val name = try { Path.of(value) } catch (failure: java.nio.file.InvalidPathException) {
            reject(ContentErrorCode.BAD_REFERENCE, source, section, field, value, "Invalid reference")
        }
        if (name.isAbsolute || name.any { it.toString() == ".." })
            reject(ContentErrorCode.BAD_REFERENCE, source, section, field, value, "Reference must remain in the content directory")
        val file = source.parent.resolve(name).normalize()
        if (!file.startsWith(source.parent)) reject(ContentErrorCode.BAD_REFERENCE, source, section, field, value, "Reference escapes content directory")
        ordinary(file, source, section, field)
        return file
    }

    /** The native defense reader additionally accepts indexed8; turret inputs remain RGBA8. */
    internal fun validatePng(path: Path, allowIndexed: Boolean = false) {
        require(Files.size(path) <= 16L * 1024 * 1024) { "PNG read bound" }
        DataInputStream(ByteArrayInputStream(Files.readAllBytes(path))).use { input ->
            require(input.readLong() == -8552249625308161526L) { "PNG signature" }
            var width = 0; var height = 0; var ended = false; var colorType = 6; var paletteEntries = 0
            val compressed = ByteArrayOutputStream()
            while (input.available() > 0) {
                val length = input.readInt()
                require(length >= 0 && length <= input.available() - 8) { "PNG chunk length" }
                val type = ByteArray(4); input.readFully(type)
                val data = ByteArray(length); input.readFully(data)
                val crc = input.readInt().toLong() and 0xffffffffL
                require(crc == CRC32().apply { update(type); update(data) }.value) { "PNG CRC" }
                val name = String(type, Charsets.US_ASCII)
                if (width == 0) {
                    require(name == "IHDR" && length == 13) { "PNG IHDR" }
                    DataInputStream(ByteArrayInputStream(data)).use { h ->
                        width = h.readInt(); height = h.readInt()
                        val depth = h.readUnsignedByte(); colorType = h.readUnsignedByte()
                        require(width > 0 && height > 0 && depth == 8 && (colorType == 6 || allowIndexed && colorType == 3) &&
                            h.readUnsignedByte() == 0 && h.readUnsignedByte() == 0 && h.readUnsignedByte() == 0) { "Expected noninterlaced RGBA8" }
                    }
                } else require(name != "IHDR") { "Duplicate PNG header" }
                when (name) {
                    "PLTE" -> if (colorType == 3) {
                        require(paletteEntries == 0 && compressed.size() == 0 && length in 3..768 && length % 3 == 0) { "PNG palette" }
                        paletteEntries = length / 3
                    }
                    "tRNS" -> if (colorType == 3) require(paletteEntries > 0 && length in 1..paletteEntries) { "PNG palette alpha" }
                    "IDAT" -> {
                        require(colorType != 3 || paletteEntries > 0) { "PNG missing palette" }
                        compressed.write(data)
                    }
                    "IEND" -> { require(length == 0 && input.available() == 0); ended = true }
                }
                if (ended) break
            }
            val row = width.toLong() * (if (colorType == 3) 1L else 4L) + 1L
            val expected = Math.multiplyExact(row, height.toLong())
            require(ended && compressed.size() > 0 && expected <= 16L * 1024 * 1024) { "PNG size or end" }
            InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).use { pixels ->
                if (colorType == 3) {
                    var previous = ByteArray(width)
                    repeat(height) {
                        val filter = pixels.read(); require(filter in 0..4) { "PNG row filter" }
                        val decoded = ByteArray(width)
                        for (x in decoded.indices) {
                            val raw = pixels.read(); require(raw >= 0) { "PNG truncated" }
                            val left = if (x == 0) 0 else decoded[x - 1].toInt() and 255
                            val above = previous[x].toInt() and 255
                            val upperLeft = if (x == 0) 0 else previous[x - 1].toInt() and 255
                            val predictor = when (filter) {
                                1 -> left
                                2 -> above
                                3 -> (left + above) / 2
                                4 -> {
                                    val p = left + above - upperLeft
                                    val a = kotlin.math.abs(p - left); val b = kotlin.math.abs(p - above)
                                    val c = kotlin.math.abs(p - upperLeft)
                                    if (a <= b && a <= c) left else if (b <= c) above else upperLeft
                                }
                                else -> 0
                            }
                            val index = (raw + predictor) and 255
                            require(index < paletteEntries) { "PNG palette index" }
                            decoded[x] = index.toByte()
                        }
                        previous = decoded
                    }
                    require(pixels.read() == -1) { "PNG overrun" }
                    return@use
                }
                val buffer = ByteArray(4096); var count = 0L
                while (true) {
                    val read = pixels.read(buffer); if (read < 0) break
                    for (i in 0 until read) if ((count + i) % row == 0L)
                        require((buffer[i].toInt() and 255) <= 4) { "PNG row filter" }
                    count += read; require(count <= expected) { "PNG overrun" }
                }
                require(count == expected) { "PNG truncated" }
            }
        }
    }
}
