package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Fixed, qualified custom AA representative; not the unrelated native continuous beam. */
internal object AaBeamContentReader {
    private val schema = UnitContentSchema.native(setOf("format", "name", "maxHp", "radius", "price", "buildSpeed",
        "image", "image_dead", "image_shadow", "image_turret", "beam_image", "beam_image_start", "beam_image_end"),
        setOf("rw-reforge.aa-beam-gunship.v1"))

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            val fields = loaded.values.getValue("core")
            fun required(key: String) = requiredContentField(fields[key], path, "core", key, "Required AA beam gunship field")
            if (required("name") != "aaBeamGunship") reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name",
                reason = "Expected aaBeamGunship")
            for ((key, expected) in mapOf("maxHp" to 2500.0, "radius" to 22.0, "price" to 6000.0)) {
                val value = required(key).toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", key, reason = "Expected a finite number")
                if (value != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key,
                    reason = "Expected qualified value $expected")
            }
            if (required("buildSpeed").toFloatOrNull() != 1f / 2100f)
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "buildSpeed", reason = "Expected qualified 35s production rate")
            fun image(key: String, width: Int, height: Int): Path = resolveReference(path, required(key), "graphics", key).also {
                validatePath(it)
                try {
                    val png = readPngMetadata(it, allowIndexed = true, allowGrayAlpha = true)
                    require(png.width == width && png.height == height) { "Expected ${width}x$height PNG" }
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid AA beam PNG: ${failure.message}")
                }
            }
            val projectile = ProjectileProfile(22.0, 15.0, 5.0, instant = true,
                damageModifiers = ProjectileDamageModifiers(shieldDamage = .75),
                beam = BeamProfile(image("beam_image", 13, 20), image("beam_image_start", 20, 20), image("beam_image_end", 20, 20)))
            val turret = image("image_turret", 16, 26)
            val weapon = NamedWeaponDefinition("1", "beam", projectile, TurretMount(0.0, 2.0), 5.0, 16.0,
                4.2, .3, turret, 1.0, recoil = WeaponRecoil(0.0), canAttackFlyingUnits = true,
                imageOffsetY = 5.0, energyUsage = .5f)
            UnitDefinition("aaBeamGunship", "CustomUnitMetadata", 22.0, 22.0, 1, image("image", 33, 52), 1.0,
                MovementProfile(MovementType.AIR, 1.0, .015, .015, 1.2, .04, true, true, .1, 50,
                    FlightProfile(35.0, 1.0, -1.0, 0.0)), emptySet(),
                combat = CombatProfile(2500.0, 280.0, 5.0, 16.0, 4.2, .3, projectile,
                    turret, image("image_dead", 33, 52), 1.0, mount = TurretMount(0.0, 2.0),
                    recoil = WeaponRecoil(0.0), idleSweep = IdleSweepProfile(20.0, 100f, .4f, 5),
                    namedWeapons = WeaponCatalog(listOf(weapon))), build = BuildProfile(6000L, 1f / 2100f),
                shadowImagePath = image("image_shadow", 33, 52), energy = EnergyProfile(6f, .01f, .05f, true))
        }
}
