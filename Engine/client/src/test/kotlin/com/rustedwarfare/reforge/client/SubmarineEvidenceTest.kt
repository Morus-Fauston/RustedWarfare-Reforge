package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class SubmarineEvidenceTest {
    @Test fun actual_session_records_authorized_depth_launch_appearance_and_the_requested_intent() {
        val base = NavalSandboxScene.load("json", "attackSubmarine").scenario
        val scene = base.copy(units = listOf(
            UnitSpawn("sub", "p1", "attackSubmarine", Position(500.0, 300.0), 90.0, -8.0),
            UnitSpawn("target", "p2", "navalUnderwaterTarget", Position(650.0, 300.0), 0.0, -8.0)),
            combatContract = CombatContract(12345, -1, mapOf("sub" to 8L, "target" to 9L), 10L))
        val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
        val sink = ClientEvidence { kind, values -> rows += kind to values }
        SandboxSession(scene, sink).use { session ->
            val initial = session.observation.fields()
            val sub = (initial["units"] as List<*>).map { it as Map<*, *> }.single { it["unitId"] == "sub" }
            val depth = assertNotNull(sub["submarine"] as? Map<*, *>)
            assertEquals(setOf("surfaceIntent", "verticalSpeed", "underwater", "surfacePresentation", "maximumSpeed", "attackRange"), depth.keys)
            assertEquals(false, depth["surfaceIntent"])
            assertEquals(true, depth["underwater"])
            assertEquals(180.0, depth["attackRange"])
            assertTrue(sub.keys.none { it in setOf("objectId", "updateKey", "randomCounter") })
            session.tick()
            val sample = rows.last { it.first == "observation" }.second["sample"] as Map<*, *>
            val shot = (sample["projectiles"] as List<*>).single() as Map<*, *>
            assertEquals("SUBMARINE_TORPEDO", shot["appearance"])
            val birth = (sample["projectileEvents"] as List<*>).map { it as Map<*, *> }.single { it["kind"] == "born" }
            assertEquals("SUBMARINE_TORPEDO", birth["appearance"])
            session.select(Position(500.0, 300.0))
            assertIs<ActionResult.Accepted>(session.submarineDepth(true))
            val action = rows.last { it.first == "action" }.second
            assertEquals("SubmarineDepthAction", action["type"])
            assertEquals(true, action["surface"])
            assertEquals(false, depth["surfaceIntent"], "Prior archive value is immutable")
        }
    }
}
