package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class BunkerFootprintTest {
    @Test fun deployment_publishes_building_cost_and_undeployment_releases_it_for_normal_path_requests() = WaterTestScene.withMap { map ->
        val original = BunkerScene.scenario(map, listOf(
            UnitSpawn("bunker", "p1", "mechBunker", Position(200.0, 180.0), 90.0)))
        val tank = CombatTestContent.scenario().unitDefinitions.getValue("c_tank")
        val probe = TestContentFactory.unitWith(tank, movement = tank.movement.copy(moveAccelerationSpeed = 0.0))
        val target = Position(320.0, 219.5)
        val scene = original.copy(unitDefinitions = original.unitDefinitions + (probe.id to probe),
            units = original.units + UnitSpawn("scout", "p1", probe.id, Position(100.0, target.y), 90.0),
            combatContract = CombatContract(12345, -1, mapOf("bunker" to 8L, "scout" to 9L), 10L))
        val context = AaBeamScene.open(scene)
        try {
            fun move(id: String) = context.submit(MoveAction(id, "p1", "scout", target, context.world))
            assertIs<ActionResult.Accepted>(move("before"))
            val initial = context.advanceToMoveOutcome(0, "scout")
            val initialPath = initial.units.single { it.unitId == "scout" }.pathPoints
            assertEquals(target, initialPath.last())
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "scout", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("deploy", "p1", "bunker", "deploy", context.world)))
            for (i in initial.stepIndex + 1..initial.stepIndex + 167) context.advance(Step(i, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(move("fixed"))
            val fixed = context.advanceToMoveOutcome(initial.stepIndex + 168, "scout")
            val detour = fixed.units.single { it.unitId == "scout" }.pathPoints
            assertEquals(target, detour.last())
            assertNotEquals(initialPath, detour, "The deployed footprint must affect the route from the same pose")
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-again", "p1", "scout", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("undeploy", "p1", "bunker", "undeploy", context.world)))
            for (i in fixed.stepIndex + 1..fixed.stepIndex + 101) context.advance(Step(i, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(move("released"))
            val released = context.advanceToMoveOutcome(fixed.stepIndex + 102, "scout")
            val openPath = released.units.single { it.unitId == "scout" }.pathPoints
            assertEquals(initialPath, openPath)
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
