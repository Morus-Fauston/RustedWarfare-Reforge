package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.Scenario
import com.rustedwarfare.reforge.core.sim.UnitSpawn
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class NamedTurretAppearanceTest {
    @Test
    fun actual_package_and_player_view_prepare_every_turret_at_its_authorized_independent_pose() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("named-weapons/json")
        val content = when (val result = ContentLoader.loadContentPacks(listOf(root.resolve("package")))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.toString())
        }
        val scene = contentPackScenario(root, ContentPackChoice(content, "named_tank"))
        val resources = SandboxRenderResources.from(scene)
        val appearance = resources.units.getValue("named_tank")
        SandboxSession(scene).use { session ->
            val view = session.observation
            val unit = view.units.single { it.unitId == "a" }
            val drawings = appearance.turretDrawings(unit, view.weapons.filter { it.unitId == "a" })
            assertEquals(2, drawings.size, "The real drawing preparation must not collapse the catalog to the old first turret")
            assertEquals(listOf("near", "far"), drawings.map { it.weaponName })
            assertEquals(listOf(Position(110.0, 198.0), Position(110.0, 222.0)), drawings.map { it.pivot })
            assertFailsWith<IllegalStateException> {
                appearance.turretDrawings(unit, view.weapons.filter { it.unitId == "a" }.take(1))
            }
            session.tick()
            val changed = session.observation
            val actual = appearance.turretDrawings(changed.units.single { it.unitId == "a" }, changed.weapons.filter { it.unitId == "a" })
            assertTrue(actual.map { it.heading }.distinct().size == 2)
            assertEquals(changed.weapons.filter { it.unitId == "a" }.map { it.pivot }, actual.map { it.pivot })
            assertTrue(actual.all { it.imagePath == appearance.turretImagePath })
        }
    }

    @Test
    fun shared_turret_image_rebinding_keeps_both_stable_weapon_bindings() {
        val (root, scene) = scene()
        val resources = SandboxRenderResources.from(scene)
        val replacement = root.resolve("units/tank_turret.png")
        val visuals = PreparedVisualAssets(VisualAssetSelection(), "named-binding", "owned-binding",
            resources.units.mapValues { (_, unit) -> VisualUnitImages(unit.imagePath, unit.backImagePath,
                replacement, unit.deadImagePath, unit.totalFrames) },
            resources.tileSets.associate { it.supportedLayout() to it.imagePath }) {}
        val bound = resources.withVisuals(visuals).units.getValue("named_tank")
        assertEquals(setOf("near", "far"), bound.weaponImages.keys)
        assertTrue(bound.weaponImages.values.all { it.imagePath == replacement })
        SandboxSession(scene).use { session ->
            val view = session.observation
            val drawings = bound.turretDrawings(view.units.single { it.unitId == "a" }, view.weapons.filter { it.unitId == "a" })
            assertEquals(2, drawings.size)
            assertTrue(drawings.all { it.imagePath == replacement })
        }
    }

    @Test
    fun a_real_dead_multi_weapon_unit_does_not_fall_back_to_drawing_a_live_primary_turret() {
        val (_, originalScene) = scene()
        val original = originalScene.unitDefinitions.getValue("named_tank")
        val combat = checkNotNull(original.combat)
        val fragile = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, 30, combat.copy(maxHp = 25.0))
        val watcher = UnitDefinition("watcher", original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale,
            original.movement.copy(moveSpeed = 0.0, moveAccelerationSpeed = 0.0, moveDecelerationSpeed = 0.0,
                maxTurnSpeed = 0.0, turnAcceleration = 0.0), original.retainedUnconsumed, 30,
            durability = DurabilityProfile(100000.0, combat.deadImagePath))
        val scene = originalScene.copy(unitDefinitions = mapOf("named_tank" to fragile, "watcher" to watcher),
            units = originalScene.units + UnitSpawn("watcher", "p1", "watcher", Position(110.0, 170.0), 0.0),
            combatContract = checkNotNull(originalScene.combatContract).copy(
                unitObjectIds = mapOf("a" to 8L, "b" to 9L, "c" to 10L, "watcher" to 11L), nextObjectId = 12L))
        val appearance = SandboxRenderResources.from(scene).units.getValue("named_tank")
        SandboxSession(scene).use { session ->
            var died = false
            repeat(200) {
                session.tick()
                val view = session.observation
                val unit = view.units.single { it.unitId == "a" }
                if (!unit.alive) {
                    assertTrue(view.weapons.none { it.unitId == "a" })
                    assertTrue(appearance.turretDrawings(unit, emptyList()).isEmpty())
                    died = true
                    return@repeat
                }
            }
            assertTrue(died, "Real projectile damage must reach the corpse rendering case")
        }
    }

    private fun scene(): Pair<Path, Scenario> {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("named-weapons/json")
        val content = when (val result = ContentLoader.loadContentPacks(listOf(root.resolve("package")))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.toString())
        }
        return root to contentPackScenario(root, ContentPackChoice(content, "named_tank"))
    }
}
