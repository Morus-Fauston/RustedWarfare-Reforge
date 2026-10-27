package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path

/** Finite native center; neither its construction action nor arbitrary replacements are supported. */
internal object CommandCenterContentReader {
    private val fields = setOf("format", "name", "maxHp", "radius", "footprint", "movementType",
        "productionUnit", "image", "image_back", "image_dead", "fogOfWarSightRange", "incomePeriod", "incomeCredits")

    fun read(path: Path): UnitDefinition {
        val values = linkedMapOf<String, String>()
        Files.readAllLines(path).forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val separator = line.indexOf('=')
            if (separator <= 0) reject(ContentErrorCode.MALFORMED_SYNTAX, path,
                reason = "Expected center field=value at line ${index + 1}")
            val key = line.substring(0, separator).trim()
            if (key !in fields) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = key, reason = "Unsupported center field")
            if (values.putIfAbsent(key, line.substring(separator + 1).trim()) != null)
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = key, reason = "Duplicate center field")
        }
        fun required(key: String): String = values[key]?.takeIf { it.isNotBlank() }
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, field = key, reason = "Required center field is absent or empty")
        fun only(key: String, expected: String) {
            if (required(key) != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = key, reason = "Only $expected is supported")
        }
        fun number(key: String, expected: Double): Double {
            val value = required(key).toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = key, reason = "Expected finite number")
            if (value != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = key, reason = "Only native value $expected is supported")
            return value
        }
        fun image(key: String): Path {
            val result = resolveReference(path, required(key), "graphics", key)
            try {
                TurretContentReader.validatePng(result, allowIndexed = true)
                val expected = when (key) {
                    "image" -> 212 to 68
                    "image_back" -> 53 to 68
                    else -> 53 to 74
                }
                java.io.DataInputStream(Files.newInputStream(result)).use { input ->
                    input.readLong(); input.readInt(); input.readInt()
                    require(input.readInt() == expected.first && input.readInt() == expected.second) {
                        "Expected native ${expected.first}x${expected.second} PNG"
                    }
                }
            } catch (failure: Exception) {
                reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid PNG: ${failure.message}")
            }
            return result
        }
        only("format", "rw-reforge.native-command-center.v1")
        only("name", "commandCenter")
        only("movementType", "NONE")
        only("productionUnit", "builder")
        only("footprint", "-1,-1,1,1")
        val hp = number("maxHp", 4000.0)
        val radius = number("radius", 30.0)
        val dead = image("image_dead")
        return UnitDefinition("commandCenter", "NativeCommandCenterFixture", radius, radius, 4, image("image"), 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            number("fogOfWarSightRange", 20.0).toInt(),
            combat = CombatProfile(hp, 280.0, 70.0, 0.0, 999.0, 0.0,
                ProjectileProfile(70.0, 180.0, 2.0, missile = NativeMissileProfile()), null, dead, 1.0,
                recoil = WeaponRecoil(offset = 0.0)),
            durability = DurabilityProfile(hp, dead, 2500.0),
            building = BuildingProfile(BuildingFootprint(-1, -1, 1, 1), image("image_back")),
            production = ProductionProfile("builder"), directHitTargetKind = DirectHitTargetKind.NATIVE_BUILDING,
            income = IncomeProfile(number("incomePeriod", 10.0).toFloat(), number("incomeCredits", 4.5).toFloat()))
    }
}
