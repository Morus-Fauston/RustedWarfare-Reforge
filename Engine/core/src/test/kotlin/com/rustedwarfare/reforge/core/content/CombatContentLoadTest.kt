package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CombatContentLoadTest {
    private fun combatPath(): Path = inputs().parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-001/20261025-e-input-01/input/units/tank.ini",
    )

    @Test
    fun loads_actual_c_tank_content_and_keeps_projectile_and_movement_speeds_distinct() {
        val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(combatPath())).value
        assertEquals("c_tank", unit.id)
        assertEquals(1.1, unit.movement.moveSpeed)
        val combat = assertNotNull(unit.combat)
        assertEquals(210.0, combat.maxHp)
        assertEquals(130.0, combat.attackRange)
        assertEquals(75.0, combat.shootDelay)
        assertEquals(25.0, combat.projectile.directDamage)
        assertEquals(60.0, combat.projectile.life)
        assertEquals(5.0, combat.projectile.speed)
        assertEquals(4.0, combat.turretTurnSpeed)
        assertEquals(1.0, combat.turretTurnAcceleration)
        assertTrue(listOf(unit.imagePath, combat.turretImagePath, combat.deadImagePath).all {
            it.isAbsolute && Files.isRegularFile(it)
        })
    }

    @Test
    fun rejects_unsupported_biology_or_soft_collision_instead_of_silently_loading_them() {
        for ((from, to, field) in listOf(
            Triple("isBio: false", "isBio: true", "isBio"),
            Triple("softCollisionOnAll: 0", "softCollisionOnAll: 1", "softCollisionOnAll"),
        )) {
            withCombatText({ it.replace(from, to) }) { path ->
                val failure = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
                assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, failure.code)
                assertEquals("core", failure.section)
                assertEquals(field, failure.field)
            }
        }
    }

    @Test
    fun legacy_variant_entry_still_rejects_combat_and_loads_weaponless_content_without_combat_state() {
        val failure = assertIs<ContentResult.Failure>(ContentLoader.loadUnitVariant(combatPath())).error
        assertEquals(ContentErrorCode.IDENTITY_CONFLICT, failure.code)
        assertEquals("overrideAndReplace", failure.field)
        val legacy = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadUnitVariant(unitPath())).value
        assertNull(legacy.combat)
        assertEquals("p001_weaponless_tank", legacy.id)
    }

    @Test
    fun rejects_bad_consumed_numbers_with_their_real_section_and_field() {
        for ((from, to, section, field) in listOf(
            listOf("maxHp: 210", "maxHp: NaN", "core", "maxHp"),
            listOf("shootDelay: 75", "shootDelay: 0", "attack", "shootDelay"),
            listOf("directDamage: 25", "directDamage: 25.5", "projectile_1", "directDamage"),
            listOf("speed: 5", "speed: Infinity", "projectile_1", "speed"),
        )) {
            withCombatText({ it.replace(from, to) }) { path ->
                val failure = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
                assertEquals(ContentErrorCode.MALFORMED_SYNTAX, failure.code)
                assertEquals(section, failure.section)
                assertEquals(field, failure.field)
                assertEquals(path.toAbsolutePath().normalize().toString(), failure.file)
            }
        }
    }

    @Test
    fun rejects_unimplemented_projectile_fields_instead_of_treating_them_as_ordinary_projectiles() {
        withCombatText({ it.replace("[projectile_1]", "[projectile_1]\ntargetGround: true") }) { path ->
            val failure = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FIELD, failure.code)
            assertEquals("projectile_1", failure.section)
            assertEquals("targetGround", failure.field)
        }
    }

    @Test
    fun missing_dead_graphic_and_duplicate_health_fail_before_a_definition_is_published() {
        withCombatText({ text ->
            text.replace(Regex("(?m)^image_wreak:.*$"), "image_wreak: p005-missing-dead-image.png")
        }) { path ->
            val failure = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, failure.code)
            assertEquals("graphics", failure.section)
            assertEquals("image_wreak", failure.field)
        }
        withCombatText({ it.replace("maxHp: 210", "maxHp: 210\nmaxHp: 210") }) { path ->
            val failure = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, failure.code)
            assertEquals("core", failure.section)
            assertEquals("maxHp", failure.field)
        }
    }

    private fun withCombatText(change: (String) -> String, check: (Path) -> Unit) {
        val source = combatPath()
        val images = mapOf("image" to "tank.png", "image_wreak" to "tank_dead.png", "image_turret" to "tank_turret.png")
        val text = Files.readString(source).replace(Regex("(?m)^(image|image_wreak|image_turret):.*$")) {
            val key = it.groupValues[1]
            "$key: ${source.parent.resolve(images.getValue(key)).toAbsolutePath().normalize()}"
        }
        val file = Files.createTempFile("p005-combat-content-", ".ini")
        try {
            Files.writeString(file, change(text))
            check(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
