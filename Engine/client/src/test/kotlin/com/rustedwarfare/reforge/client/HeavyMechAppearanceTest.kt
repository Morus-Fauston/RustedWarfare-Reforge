package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

internal object HeavyMechClientScene {
    fun scenario(): Scenario {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val loaded = ContentLoader.loadContentPack(root.resolve("heavy-mech-production/json/package"))
        val unit = assertIs<ContentResult.Success<LoadedContentPack>>(loaded).value.units.getValue("mechHeavyMissile")
        return ControlledScenario.load(root.resolve("mech-production/json")).copy(
            scenarioId = "heavy-client-appearance", unitDefinitions = mapOf(unit.id to unit),
            units = listOf(UnitSpawn("a", "p1", unit.id, Position(200.0, 200.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L), 9L),
            economyContract = null, maintenanceContract = null, automaticVision = null)
    }
}

class HeavyMechAppearanceTest {
    @Test fun authorized_four_weapon_states_draw_only_the_small_laser_and_parent_with_their_own_images_and_offsets() {
        val scene = HeavyMechClientScene.scenario()
        val appearance = SandboxRenderResources.from(scene).units.getValue("mechHeavyMissile")
        SandboxSession(scene).use { session ->
            val view = session.observation
            val unit = view.units.single()
            assertEquals(listOf("smallLaser", "1", "2", "3"), view.weapons.map { it.weaponName })
            val drawings = appearance.turretDrawings(unit, view.weapons)
            assertEquals(listOf("smallLaser", "1"), drawings.map { it.weaponName }, "Invisible missile children retain state but draw no turret")
            val small = drawings.first()
            val parent = drawings.last()
            assertEquals("small_turret.png", small.imagePath.fileName.toString())
            assertEquals("turret.png", parent.imagePath.fileName.toString())
            assertEquals(1.0, small.imageScale)
            assertEquals(1.0, parent.imageScale)
            assertEquals(Position(205.0, 187.0), view.weapons.first().pivot, "The physical mount remains unchanged")
            assertEquals(200.0, small.pivot.x, 1e-10, "drawOffsetY5 follows the small turret's westward heading")
            assertEquals(187.0, small.pivot.y, 1e-10)
            assertEquals(view.weapons.single { it.weaponName == "1" }.pivot, parent.pivot)
            assertEquals(27.5, appearance.drawSize(UnitImageSlot.BODY, 25, 39).width, 1e-10)
            assertEquals(42.9, appearance.drawSize(UnitImageSlot.BODY, 25, 39).height, 1e-10)
            val projection = MapProjection(600.0, 400.0, MapArea(0.0, 0.0, 600.0, 400.0))
            val body = MechPresentation.project(view, projection).single()
            assertEquals(4, body.legs.size)
            assertEquals(-0f, body.rotation)
            assertEquals(200f, body.bodyX)
            assertEquals(200f, body.bodyY)
        }
    }
}
