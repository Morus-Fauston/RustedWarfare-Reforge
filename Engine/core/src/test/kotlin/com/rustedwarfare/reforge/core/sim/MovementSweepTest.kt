package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class MovementSweepTest {
    @Test fun hidden_wall_between_clear_endpoints_blocks_actual_motion_without_changing_the_plan() {
        val source = TestContentFactory.openScenario()
        val tank = source.unitDefinitions.getValue("tank")
        val fast = TestContentFactory.unitWith(tank, radius = 1.0, movement = tank.movement.copy(
            moveSpeed = 100.0, moveAccelerationSpeed = 100.0))
        val paths = mutableListOf<List<Position>>()
        for (blocked in listOf(false, true)) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
                map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600),
                    BlockingGrid(30, 20, BooleanArray(600) { blocked && it == 5 * 30 + 8 })),
                unitDefinitions = mapOf("tank" to fast),
                units = listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0))))).context
            try {
                context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY,
                    PathInformationSource.PLAYER_KNOWLEDGE, false))
                context.submit(MoveAction("move", "p1", "u1", Position(350.0, 110.0), context.world))
                paths += context.observe().units.single().pathPoints
                val result = context.advance(Step(0, 1.0, "sim-tick"))
                val unit = result.units.single()
                // Hidden tile spans x=160..180. Both x=110 and x=210 are independently clear.
                assertEquals(if (blocked) Position(110.0, 110.0) else Position(210.0, 110.0), unit.position)
                assertEquals(if (blocked) 0.0 else 100.0, unit.speed)
                assertEquals(if (blocked) MovementState.BLOCKED else MovementState.MOVING, unit.movementState)
                assertEquals(blocked, result.events.any { it.kind == "movement-blocked" })
            } finally { context.close() }
        }
        assertEquals(listOf(listOf(Position(350.0, 110.0)), listOf(Position(350.0, 110.0))), paths)
    }
}
