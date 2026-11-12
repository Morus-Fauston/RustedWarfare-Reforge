package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** The fixed bomber has two independent target categories and two projectile declarations. */
internal object BomberContentReader {
    private val mount = mapOf("x" to "0", "y" to "0", "size" to "1.1", "turnSpeed" to "20", "energyUsage" to "1",
        "invisible" to "true", "canAttackFlyingUnits" to "false")
    private val shot = mapOf("life" to "250", "speed" to "0.3", "targetSpeed" to "1.7", "deflectionPower" to "3",
        "drawSize" to "1", "color" to "#000000", "trailEffect" to "false")
    private val expected = mapOf(
        "core" to mapOf("name" to "bomber", "maxHp" to "3000", "radius" to "16", "displayRadius" to "19",
            "price" to "4000", "techLevel" to "2", "buildSpeed" to "0.00025", "selfRegenRate" to "0.1",
            "energyMax" to "3", "energyRegen" to "0.002", "energyNeedsToRechargeToFull" to "true"),
        "graphics" to mapOf("total_frames" to "1"),
        "movement" to mapOf("movementType" to "AIR", "moveSpeed" to "0.85", "moveAccelerationSpeed" to "0.01",
            "moveDecelerationSpeed" to "0.01", "maxTurnSpeed" to "1.2", "turnAcceleration" to "0.04",
            "moveSlidingMode" to "false", "moveIgnoringBody" to "false", "moveSlidingDir" to "25",
            "landOnGround" to "onlyIdle", "slowDeathFall" to "true"),
        "attack" to mapOf("canAttack" to "true", "canAttackFlyingUnits" to "false", "canAttackLandUnits" to "true",
            "canAttackUnderwaterUnits" to "true", "maxAttackRange" to "45", "shootDelay" to "30", "attackMovement" to "bomber"),
        "turret_1" to (mount + mapOf("projectile" to "1", "canAttackLandUnits" to "true", "canAttackUnderwaterUnits" to "false")),
        "turret_2" to (mount + mapOf("projectile" to "2", "canAttackLandUnits" to "false", "canAttackUnderwaterUnits" to "true")),
        "projectile_1" to (shot + mapOf("targetGround" to "true", "areaDamage" to "370", "areaRadius" to "65", "largeHitEffect" to "true")),
        "projectile_2" to (shot + mapOf("targetGround" to "false", "directDamage" to "70", "largeHitEffect" to "false")))
    private val images = setOf("image", "image_dead", "image_shadow")
    private val text = setOf("name", "movementType", "landOnGround", "attackMovement", "projectile", "color") + images
    private val booleans = setOf("energyNeedsToRechargeToFull", "moveSlidingMode", "moveIgnoringBody", "slowDeathFall",
        "canAttack", "canAttackFlyingUnits", "canAttackLandUnits", "canAttackUnderwaterUnits", "invisible",
        "targetGround", "largeHitEffect", "trailEffect")
    private val fields = expected.mapValues { (section, values) -> if (section == "graphics") values.keys + images else values.keys }
    private val schema = UnitContentSchema(fields, fields.values.flatten().toSet() - text - booleans, booleans, text, dynamicWeapons = false)

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            fun value(section: String, field: String) = requiredContentField(loaded.values[section]?.get(field),
                path, section, field, "Required bomber field")
            for ((section, values) in expected) for ((field, wanted) in values) {
                val actual = value(section, field)
                val number = wanted.toDoubleOrNull()
                val matches = if (field in schema.numericFields) actual.toDoubleOrNull() == number else actual == wanted
                if (!matches) {
                    val error = if (field == "projectile") ContentErrorCode.BAD_REFERENCE else ContentErrorCode.UNSUPPORTED_FEATURE
                    reject(error, path, section, field, reason = "Expected qualified bomber value $wanted")
                }
            }
            fun image(field: String): Path {
                val source = loaded.origins["graphics" to field]?.path ?: path
                val file = resolveReference(source, value("graphics", field), "graphics", field)
                validatePath(file)
                try {
                    val png = readPngMetadata(file, allowIndexed = true, allowGrayAlpha = true)
                    require(png.width == 45 && png.height == 47) { "Expected 45x47 PNG" }
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    reject(ContentErrorCode.BAD_REFERENCE, source, "graphics", field, reason = "Invalid bomber PNG: ${failure.message}")
                }
                return file
            }
            val dead = image("image_dead")
            UnitDefinition(BomberRules.ID, "CustomUnitMetadata", 16.0, 19.0, 1, image("image"), 1.0,
                BomberRules.movement, emptySet(), combat = BomberRules.combat(dead),
                build = BomberRules.build, shadowImagePath = image("image_shadow"),
                energy = BomberRules.energy, selfRegenRate = .1f, techLevel = 2)
        }
}
