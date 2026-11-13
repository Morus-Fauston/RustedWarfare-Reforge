package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Qualified native producer; the disabled custom three-state INIs are not this capability. */
internal object AmphibiousJetContentReader {
    private val schema = UnitContentSchema.native(setOf("format", "name", "maxHp", "radius", "price", "buildSpeed",
        "image", "image_dead", "image_shadow", "image_wing_left", "image_wing_right"),
        setOf("rw-reforge.native-amphibious-jet.v1"))

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            val values = loaded.values.getValue("core")
            fun required(key: String) = requiredContentField(values[key], path, "core", key, "Required native amphibious jet field")
            if (required("name") != "amphibiousJet") reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name",
                reason = "Expected native amphibiousJet")
            for ((key, expected) in mapOf("maxHp" to 530.0, "radius" to 12.0, "price" to 2000.0, "buildSpeed" to .001)) {
                val number = required(key).toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", key, reason = "Expected a finite native value")
                if (number != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key,
                    reason = "Expected native value $expected")
            }
            fun image(key: String): Path = resolveReference(path, required(key), "graphics", key).also { image ->
                validatePath(image)
                try {
                    val png = readPngMetadata(image, allowIndexed = true, allowGrayAlpha = true)
                    val wing = key in setOf("image_wing_left", "image_wing_right")
                    // Content and PC mapped resources share the client's north-facing basis.
                    require(png.width == if (wing) 30 else 15)
                    require(png.height == if (wing) 14 else 26)
                } catch (failure: Exception) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid native jet PNG: ${failure.message}")
                }
            }
            val projectile = ProjectileProfile(45.0, 10.0, 4.0, instant = true, lightning = true,
                damageModifiers = ProjectileDamageModifiers(shieldDeflection = .1, building = .5))
            val weapons = listOf("left", "right", "body").mapIndexed { index, name ->
                NamedWeaponDefinition(name, "lightning", projectile,
                    if (index == 2) TurretMount() else TurretMount(if (index == 0) -12.0 else 12.0, 7.0),
                    110.0, 5.0, 4.0, .35, null, 1.0, recoil = WeaponRecoil(0.0),
                    fireSound = FireSoundProfile.AMPHIBIOUS_LIGHTNING, canAttackFlyingUnits = true,
                    canAttackUnderwaterUnits = true, warmup = 25.0 + index * 10.0,
                    warmupCallDownRate = .2, canShoot = index != 2, invisible = true, turnDeceleration = .38)
            }
            UnitDefinition("amphibiousJet", "NativeAmphibiousJet", 12.0, 13.0, 1, image("image"), 1.0,
                MovementProfile(MovementType.AIR, 1.4, .03, .1, 3.8, .3, false, false, .6), emptySet(),
                combat = CombatProfile(530.0, 170.0, 110.0, 5.0, 4.0, .35, projectile, null,
                    image("image_dead"), 1.0, recoil = WeaponRecoil(0.0), namedWeapons = WeaponCatalog(weapons), turretTurnDeceleration = .38),
                build = BuildProfile(2000L, .001f), shadowImagePath = image("image_shadow"),
                amphibiousJet = AmphibiousJetProfile(image("image_wing_left"), image("image_wing_right")))
        }
}
