package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class ShieldVisibilityTest {
    @Test fun a_hidden_shield_hit_does_not_change_the_observer_units_events_or_sample_identity() {
        val hide: (Scenario) -> Scenario = { base ->
            val tank = base.unitDefinitions.getValue("c_tank")
            val observer = UnitDefinition("observer", tank.className, tank.radius, tank.displayRadius,
                tank.totalFrames, tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 1)
            base.copy(unitDefinitions = base.unitDefinitions + (observer.id to observer),
                players = base.players + Player("p3", PlayerControl.NONE),
                units = listOf(UnitSpawn("observer", "p1", observer.id, Position(50.0, 50.0), 0.0),
                    UnitSpawn("probe", "p3", tank.id, Position(500.0, 400.0), 90.0),
                    UnitSpawn("heavy", "p2", "mechHeavyMissile", Position(600.0, 400.0), 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("observer" to 7L, "probe" to 8L, "heavy" to 9L), 10L),
                automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        }
        withShieldDuel(0.0, hide) { quiet -> withShieldDuel(40.0, hide) { damaged ->
            var spent = false
            for (step in 0L..59L) {
                quiet.advance(Step(step, 1.0, "sim-tick"))
                val host = damaged.advance(Step(step, 1.0, "sim-tick"))
                spent = spent || host.events.any { it.kind == "shield-damaged" && it.unitId == "heavy" }
                val expected = quiet.observePlayer(quiet.world, "p1")
                val actual = damaged.observePlayer(damaged.world, "p1")
                assertEquals(listOf("observer"), actual.units.map { it.unitId })
                assertEquals(expected.units, actual.units)
                assertEquals(expected.events, actual.events)
                assertEquals(expected.projectiles, actual.projectiles)
                assertEquals(expected.projectileEvents, actual.projectileEvents)
                assertEquals(expected.weapons, actual.weapons)
                assertEquals(expected.sampleId, actual.sampleId)
            }
            assertTrue(spent, "A real hidden hit must occur; an idle hidden world proves no privacy boundary")
            assertTrue(damaged.observe().units.single { it.unitId == "heavy" }.shield!!.current < 500f)
        } }
    }
}
