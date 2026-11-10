package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class BunkerFootprintTest {
    @Test fun deployment_publishes_building_cost_and_undeployment_releases_it_for_normal_path_requests() = WaterTestScene.withMap { map ->
        val original = BunkerScene.scenario(map)
        val probe = CombatTestContent.scenario().unitDefinitions.getValue("c_tank")
        val scene = original.copy(unitDefinitions = original.unitDefinitions + (probe.id to probe),
            units = original.units + UnitSpawn("scout", "p1", probe.id, Position(100.0, 210.0), 90.0),
            combatContract = CombatContract(12345, -1, mapOf("bunker" to 8L, "scout" to 9L), 10L))
        val context = AaBeamScene.open(scene)
        try {
            fun move(id: String) = context.submit(MoveAction(id, "p1", "scout", Position(320.0, 210.0), context.world))
            assertIs<ActionResult.Accepted>(move("before"))
            assertTrue(context.observe().units.single { it.unitId == "scout" }.pathPoints.all { it.y in 180.0..<240.0 })
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "scout", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("deploy", "p1", "bunker", "deploy", context.world)))
            for (i in 0L..166L) context.advance(Step(i, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(move("fixed"))
            assertTrue(context.observe().units.single { it.unitId == "scout" }.pathPoints.any { it.y < 180.0 || it.y >= 240.0 })
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-again", "p1", "scout", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("undeploy", "p1", "bunker", "undeploy", context.world)))
            for (i in 167L..267L) context.advance(Step(i, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(move("released"))
            assertTrue(context.observe().units.single { it.unitId == "scout" }.pathPoints.all { it.y in 180.0..<240.0 })
        } finally { context.close() }
    }

    @Test fun a_legal_mobile_center_can_deploy_near_the_edge_then_undeploy() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(BunkerScene.scenario(map, listOf(
            UnitSpawn("bunker", "p1", "mechBunker", Position(30.0, 200.0), 90.0))))
        try {
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("deploy", "p1", "bunker", "deploy", context.world)))
            for (i in 0L..166L) context.advance(Step(i, 1.0, "sim-tick"))
            assertEquals("mechBunkerDeployed", context.observe().units.single().typeId)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("undeploy", "p1", "bunker", "undeploy", context.world)))
            for (i in 167L..267L) context.advance(Step(i, 1.0, "sim-tick"))
            assertEquals("mechBunker", context.observe().units.single().typeId)
        } finally { context.close() }
    }
}
