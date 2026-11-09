package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class DropshipProjectionTest {
    @Test fun an_enemy_transport_keeps_a_shadow_without_exposing_its_cargo() {
        val scenario = DropshipSandboxScene.load().scenario.copy(units = listOf(
            UnitSpawn("air", "p2", "dropship", Position(200.0, 150.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("air" to 8L), 9L))
        SandboxSession(scenario).use { session ->
            repeat(100) { session.tick() }
            val enemy = session.observation.units.single { it.unitId == "air" }
            assertNull(enemy.transport)
            assertTrue(enemy.height > 0.0)
            assertNotNull(session.renderResources.units.getValue(enemy.typeId).shadowImagePath)
            val frame = FlightPresentation.project(session.observation, MapProjection(800.0, 600.0)).single()
            assertTrue(frame.drawShadow, "A visible airborne enemy needs the same ground shadow")
        }
    }

    @Test fun an_actual_dead_transport_keeps_its_body_at_the_falling_height() {
        val base = DropshipSandboxScene.load().scenario
        val scenario = base.copy(units = listOf(
            UnitSpawn("air", "p1", "dropship", Position(200.0, 150.0), 90.0),
            UnitSpawn("gun", "p2", "helicopter", Position(260.0, 150.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("air" to 8L, "gun" to 9L), 10L))
        SandboxSession(scenario).use { session ->
            var step = 0
            while (session.observation.units.single { it.unitId == "air" }.alive && step++ < 5000) session.tick()
            val dead = session.observation.units.single { it.unitId == "air" }
            assertFalse(dead.alive)
            assertTrue(dead.height > 0.0)
            val projection = MapProjection(800.0, 600.0)
            val frame = assertNotNull(FlightPresentation.project(session.observation, projection).singleOrNull { it.unitId == "air" })
            assertEquals(projection.y(dead.position.y - dead.height), frame.bodyY)
            assertNull(session.renderResources.units.getValue("dropship").rotorImagePath)
            assertNotNull(session.renderResources.units.getValue("dropship").shadowImagePath)
        }
    }

    @Test fun a_visible_enemy_wreck_falls_without_exposing_its_cargo() {
        val scenario = DropshipSandboxScene.load().scenario.copy(units = listOf(
            UnitSpawn("air", "p2", "dropship", Position(200.0, 150.0), 90.0),
            UnitSpawn("gun", "p1", "helicopter", Position(260.0, 150.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("air" to 8L, "gun" to 9L), 10L))
        SandboxSession(scenario).use { session ->
            var step = 0
            while (session.observation.units.single { it.unitId == "air" }.alive && step++ < 5000) session.tick()
            val dead = session.observation.units.single { it.unitId == "air" }
            assertFalse(dead.alive)
            assertNull(dead.transport)
            assertTrue(dead.height > 0.0)
            for (scale in listOf(1.0, 1.25)) {
                val projection = MapProjection(800.0, 600.0, MapArea(0.0, 0.0, 800.0 * scale, 600.0 * scale))
                val frame = assertNotNull(FlightPresentation.project(session.observation, projection).singleOrNull { it.unitId == "air" })
                assertEquals(projection.y(dead.position.y - dead.height), frame.bodyY)
                assertFalse(frame.drawShadow)
            }
            repeat(5) { session.tick() }
            val falling = session.observation.units.single { it.unitId == "air" }
            assertNull(falling.transport)
            assertTrue(falling.height < dead.height && falling.height > 0.0)
            val projection = MapProjection(800.0, 600.0)
            val frame = assertNotNull(FlightPresentation.project(session.observation, projection).singleOrNull { it.unitId == "air" })
            assertEquals(projection.y(falling.position.y - falling.height), frame.bodyY)
        }
    }
}
