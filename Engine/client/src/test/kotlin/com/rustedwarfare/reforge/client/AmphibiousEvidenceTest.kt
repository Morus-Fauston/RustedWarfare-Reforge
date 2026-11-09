package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class AmphibiousEvidenceTest {
    @Test fun session_archives_authorized_flight_state_requested_intent_and_frozen_lightning_endpoints() {
        val base = AmphibiousSandboxScene.load().scenario
        val scene = base.copy(units = listOf(
            UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 300.0), 90.0),
            UnitSpawn("target", "p2", "helicopter", Position(600.0, 300.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("jet" to 8L, "target" to 9L), 10L))
        val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
        SandboxSession(scene, ClientEvidence { kind, values -> rows += kind to values }).use { session ->
            val first = session.observation.fields()
            val jet = (first["units"] as List<*>).map { it as Map<*, *> }.single { it["unitId"] == "jet" }
            val flight = assertNotNull(jet["amphibiousJet"] as? Map<*, *>)
            assertEquals(setOf("flyingIntent", "verticalSpeed", "underwater", "surfacePresentation", "movementType",
                "maximumSpeed", "attackRange", "wingFold", "phase", "sinkSpeed", "settled", "canDive"), flight.keys)
            assertEquals(true, flight["flyingIntent"])
            assertEquals("AIR", flight["movementType"])
            repeat(240) { session.tick() }
            val samples = rows.filter { it.first == "observation" }.map { it.second["sample"] as Map<*, *> }
            val shot = samples.flatMap { it["projectiles"] as List<*> }.map { it as Map<*, *> }
                .first { it["appearance"] == "AMPHIBIOUS_LIGHTNING" }
            val line = assertNotNull(shot["lightning"] as? Map<*, *>)
            assertEquals(mapOf("x" to 600.0, "y" to 300.0), line["end"])
            assertTrue((line["endHeight"] as Double).isFinite())
            val born = samples.flatMap { it["projectileEvents"] as List<*> }.map { it as Map<*, *> }
                .first { it["kind"] == "born" && it["appearance"] == "AMPHIBIOUS_LIGHTNING" }
            assertNotNull(born["lightning"])
            session.select(session.observation.units.single { it.unitId == "jet" }.displayedBodyPosition())
            assertIs<ActionResult.Accepted>(session.amphibiousFlight(false))
            val action = rows.last { it.first == "action" }.second
            assertEquals("AmphibiousFlightAction", action["type"])
            assertEquals(false, action["flying"])
            assertEquals(true, flight["flyingIntent"], "Previously published state remains immutable")
        }
    }
}
