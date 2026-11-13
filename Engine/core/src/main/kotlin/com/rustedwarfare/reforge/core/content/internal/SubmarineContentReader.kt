package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Finite native submarine through the common syntax, source and package guards. */
internal object SubmarineContentReader {
    private val schema = UnitContentSchema.native(
        setOf("format", "name", "maxHp", "radius", "price", "buildSpeed", "image", "image_dead"),
        setOf("rw-reforge.native-submarine.v1"))

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            val values = loaded.values.getValue("core")
            fun value(key: String) = requiredContentField(values[key], path, "core", key, "Required native submarine field")
            fun number(key: String, expected: Double) {
                val actual = value(key).toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", key, reason = "Expected a finite native value")
                if (actual != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key,
                    reason = "Expected native submarine value $expected")
            }
            if (value("name") != "attackSubmarine") reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name",
                reason = "Expected attackSubmarine")
            number("maxHp", 260.0); number("radius", 15.0); number("price", 800.0); number("buildSpeed", .001)
            fun image(key: String): Path = resolveReference(path, value(key), "graphics", key).also { image ->
                validatePath(image)
                try { readPngMetadata(image, allowIndexed = true, allowGrayAlpha = true) }
                catch (failure: Exception) { reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key,
                    reason = "Invalid native submarine PNG: ${failure.message}") }
            }
            val projectile = ProjectileProfile(42.0, 190.0, 2.0, ballistic = CustomBallisticProfile(40f, 60f, 2f))
            val weapon = NamedWeaponDefinition("1", "surface", projectile, TurretMount(), 170.0, 10.0, 2.5, .08,
                null, 1.0, recoil = WeaponRecoil(0.0), canAttackFlyingUnits = true, canAttackUnderwaterUnits = true,
                invisible = true, fireSound = FireSoundProfile.SUBMARINE_MISSILE)
            UnitDefinition("attackSubmarine", "NativeSubmarine", 15.0, 13.0, 1, image("image"), 1.0,
                MovementProfile(MovementType.WATER, .8, .018, .1, 1.2, .06, false, false, .6), emptySet(),
                combat = CombatProfile(260.0, 250.0, 170.0, 10.0, 2.5, .08, projectile, null,
                    image("image_dead"), 1.0, recoil = WeaponRecoil(0.0), fireSound = FireSoundProfile.SUBMARINE_MISSILE,
                    namedWeapons = WeaponCatalog(listOf(weapon))),
                build = BuildProfile(800L, .001f), submarine = SubmarineProfile.NATIVE)
        }
}
