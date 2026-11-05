package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class NavalProjectionTest {
    @Test fun real_session_death_and_the_first_sink_step_project_the_dead_water_body_at_two_scales() {
        val base = NavalSandboxScene.load().scenario
        val ship = base.unitDefinitions.getValue("heavyBattleship")
        // Explicit HP1 lifetime instrument; incoming shots and the loaded body/movement/dead image are real.
        val hull = UnitDefinition(ship.id, ship.className, ship.radius, ship.displayRadius, ship.totalFrames,
            ship.imagePath, ship.imageScale, ship.movement, ship.retainedUnconsumed, 0,
            durability = DurabilityProfile(1.0, ship.combat!!.deadImagePath))
        val scene = base.copy(unitDefinitions = mapOf("hull" to hull, "attacker" to ship),
            units = listOf(UnitSpawn("hull", "p1", "hull", Position(500.0, 300.0), 90.0),
                UnitSpawn("attacker", "p2", "attacker", Position(650.0, 300.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("hull" to 8L, "attacker" to 9L), 10L))
        for (scale in listOf(1.0, 2.0)) {
            val projection = MapProjection(800.0, 600.0, MapArea(0.0, 0.0, 800.0 * scale, 600.0 * scale))
            SandboxSession(scene).use { session ->
                val initial = session.observation
                val old = WaterPresentation.project(initial, projection)
                assertEquals((500 * scale).toFloat(), old.single { it.unitId == "hull" }.bodyX)
                assertEquals((300 * scale).toFloat(), old.single { it.unitId == "hull" }.bodyY)
                assertFailsWith<UnsupportedOperationException> { (old as MutableList).clear() }
                var q = 0
                while (session.observation.units.single { it.unitId == "hull" }.alive && q++ < 100) session.tick()
                val corpse = session.observation.units.single { it.unitId == "hull" }
                assertFalse(corpse.alive)
                assertTrue(session.renderResources.units.getValue("hull").isDrawn(corpse))
                assertEquals(0.0, corpse.height)
                assertEquals((300 * scale).toFloat(), WaterPresentation.project(session.observation, projection).single { it.unitId == "hull" }.bodyY)
                session.tick()
                val sinking = session.observation.units.single { it.unitId == "hull" }
                assertEquals(0xba51b717.toInt(), sinking.height.toFloat().toRawBits())
                val frame = WaterPresentation.project(session.observation, projection).single { it.unitId == "hull" }
                assertEquals((500 * scale).toFloat(), frame.bodyX)
                assertEquals((299.9992000000202 * scale).toFloat(), frame.bodyY)
                assertEquals(-90f, frame.bodyRotation)
                assertEquals(old, WaterPresentation.project(initial, projection))
            }
        }
    }
}
