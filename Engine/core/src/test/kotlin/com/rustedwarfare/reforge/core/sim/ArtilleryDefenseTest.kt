package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class ArtilleryDefenseTest {
    @Test fun publicly_fired_ground_shots_move_on_the_deflection_frame_and_resolve_hit_then_life_then_interception() {
        for ((distance, life, expected) in listOf(Triple(37f, 8.5f, "impact"),
            Triple(100f, 8.5f, "expired"), Triple(100f, 240f, "intercepted"))) {
            val base = GroundTestContent.scene(distance, life, vertical = 0f, gravity = 0f)
            val defense = PointDefenseTestContent.native()
            val scene = base.copy(unitDefinitions = base.unitDefinitions + (defense.id to defense),
                units = base.units + UnitSpawn("d", "p2", defense.id, Position(350.0, 210.0), 0.0),
                combatContract = CombatContract(12345, -1, mapOf("t" to 11L, "z" to 12L, "d" to 13L), 15L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val rows = (0L..10L).map { c.advance(Step(it, 1.0, "sim-tick")) }
                val shot = rows.first().projectileEvents.single { it.kind == "born" }.projectile.objectId
                val events = rows.flatMap { it.projectileEvents }.filter { it.projectile.objectId == shot }
                assertEquals(listOf("born", "deflected", expected), events.map { it.kind })
                val beforeMove = events[1].projectile
                val ending = events[2].projectile
                assertEquals(8f, beforeMove.age)
                assertEquals(9f, ending.age)
                assertTrue(ending.intercepted)
                assertTrue(ending.position.x > beforeMove.position.x)
                assertNull(events.last().damage)
                assertNull(ending.targetUnitId)
                assertEquals(if (expected == "impact") 9920.0 else 10000.0, rows.last().units.single { it.unitId == "z" }.health)
                assertTrue(rows[8].projectiles.none { it.objectId == shot })
                assertEquals(1, events.count { it.kind in setOf("impact", "expired", "intercepted") })
            } finally { c.close() }
        }
    }
}
