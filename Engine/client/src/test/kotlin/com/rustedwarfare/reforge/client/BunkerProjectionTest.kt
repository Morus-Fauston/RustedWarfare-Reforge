package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class BunkerProjectionTest {
    @Test fun raised_body_selection_named_turret_and_feet_share_height_without_changing_ground_coordinates() {
        val loaded = BunkerSandboxScene.load()
        val scene = loaded.scenario.copy(units = listOf(
            UnitSpawn("bunker", "p1", "mechBunker", Position(200.0, 200.0), 90.0, 3.0)),
            combatContract = CombatContract(12345, -1, mapOf("bunker" to 1L), 2L))
        val projection = MapProjection(600.0, 400.0, MapArea(0.0, 0.0, 600.0, 400.0))
        val resources = SandboxRenderResources.from(scene)
        SandboxSession(scene).use { session ->
            val view = session.observation
            val unit = view.units.single()
            assertEquals(Position(200.0, 200.0), unit.position)
            assertEquals(Position(200.0, 197.0), unit.displayedBodyPosition())
            val frame = MechPresentation.project(view, projection, resources).single()
            assertEquals(200f, frame.bodyX)
            assertEquals(203f, frame.bodyY)
            assertTrue(frame.drawShadow)
            assertEquals(200f, frame.shadowX)
            assertEquals(200f, frame.shadowY)
            // Fixed source leg_1 attach=(-5,10), foot=(-22,15), initial leg height=.1.
            assertEquals(208f, frame.legs.first().anchorY)
            assertEquals(225.1f, frame.legs.first().footY, .0001f)
            val turret = resources.units.getValue(unit.typeId).turretDrawings(unit, view.weapons).single()
            assertEquals(Position(200.0, 197.0), turret.pivot)
            assertEquals(3.0, unit.height)
        }
    }
}
