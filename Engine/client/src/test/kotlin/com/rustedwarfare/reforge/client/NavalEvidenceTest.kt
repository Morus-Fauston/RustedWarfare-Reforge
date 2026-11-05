package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class NavalEvidenceTest {
    @Test fun real_session_evidence_keeps_the_initial_and_reversing_ordinary_water_pose_without_host_fields() {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val folder = root.resolve("naval-production/ini")
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(folder.resolve("maps/naval-shore.tmx"))).value
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(folder.resolve("production-package"))).value
        val input = Scenario("naval-evidence", map, pack.units,
            listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            listOf(UnitSpawn("ship", "p1", "heavyBattleship", Position(500.0, 300.0), 90.0)),
            CoordinateModel("naval.coordinates", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("naval.time", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, mapOf("ship" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 12000L, "p2" to 0L)))
        val rows = mutableListOf<Pair<String, Map<String, Any?>>>()
        val evidence = ClientEvidence { kind, fields -> rows += kind to fields }
        fun unit(phase: String): Map<*, *> {
            val row = rows.last { it.first == "observation" && it.second["phase"] == phase }
            val sample = assertNotNull(row.second["sample"] as? Map<*, *>)
            return (sample["units"] as List<*>).single() as Map<*, *>
        }
        SandboxSession(input, evidence).use { session ->
            val initial = unit("initial")
            assertEquals(mapOf("reversing" to false, "sinkSpeed" to 0f, "settled" to false), initial["waterPose"])
            session.select(Position(500.0, 300.0))
            assertIs<ActionResult.Accepted>(session.move(Position(450.0, 300.0)))
            session.tick()
            val moved = unit("step")
            val pose = assertNotNull(moved["waterPose"] as? Map<*, *>)
            assertEquals(setOf("reversing", "sinkSpeed", "settled"), pose.keys)
            assertEquals(true, pose["reversing"])
            assertEquals(0.0, moved["height"])
            assertTrue(moved["mechPose"] == null && moved["flightPose"] == null)
            assertTrue(moved.keys.none { it in setOf("objectId", "updateKey", "randomCounter") })
            assertEquals(false, (initial["waterPose"] as Map<*, *>)["reversing"])
        }
        assertEquals(true, (unit("before-close")["waterPose"] as Map<*, *>)["reversing"])
    }
}
