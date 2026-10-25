package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CombatSandboxSessionTest {
    private fun root(): Path = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-001/20261025-e-pc-representative-01/input")

    @Test
    fun actual_fixed_scene_and_player_session_chase_fire_damage_clear_dead_selection_and_reopen() {
        val input = ControlledScenario.load(root())
        assertEquals("p005-combat", input.scenarioId)
        assertEquals(3, input.units.size)
        val records = mutableListOf<Pair<String, Map<String, Any?>>>()
        val sink = ClientEvidence { kind, values -> records += kind to values }
        SandboxSession(input, sink).use { session ->
            session.select(Position(110.0, 210.0))
            assertEquals("a", session.selectedId)
            assertIs<ActionResult.Accepted>(session.command(Position(350.0, 210.0)))
            assertEquals("b", session.observation.units.single { it.unitId == "a" }.attackTargetId)
            val old = session.frame
            var flew = false
            var damaged = false
            var dead = false
            repeat(1500) {
                if (!dead) {
                    session.tick()
                    flew = flew || session.observation.projectiles.isNotEmpty()
                    damaged = damaged || session.observation.units.any { checkNotNull(it.health) < 210.0 }
                    dead = session.observation.units.any { !it.alive }
                }
            }
            assertTrue(session.observation.units.single { it.unitId == "a" }.position.x > 110.0)
            assertTrue(flew && damaged && dead)
            assertTrue(old.observation.units.all { it.alive && it.health == 210.0 })
            assertTrue(session.observation.units.filter { it.ownerPlayerId != "p1" }.all { it.cooldown == null })
            val corpse = session.observation.units.first { !it.alive }
            session.select(corpse.position)
            assertNull(session.selectedId)
            assertTrue(records.any { it.first == "action" && it.second["type"] == "AttackAction" && it.second["targetUnitId"] == "b" })
        }
        SandboxSession(input).use { reopened ->
            assertTrue(reopened.observation.units.all { it.alive && it.health == 210.0 })
            assertTrue(reopened.observation.projectiles.isEmpty())
            assertNull(reopened.selectedId)
        }
    }
}
