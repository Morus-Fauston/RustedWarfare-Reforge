package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NaturalMovementContractV2Test {
    private fun collisionContext(blocked: Boolean): SimulationContext {
        val source = TestContentFactory.openScenario()
        val original = source.unitDefinitions.getValue("tank")
        assertEquals(11.0, original.radius)
        val fast = TestContentFactory.unitWith(original,
            movement = original.movement.copy(moveSpeed = 20.0, moveAccelerationSpeed = 20.0))
        val cells = BooleanArray(600)
        if (blocked) {
            cells[2 * 30 + 5] = true
            cells[6 * 30 + 7] = true
        }
        val scenario = source.copy(
            map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, cells)),
            unitDefinitions = mapOf("tank" to fast),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0)),
            automaticVision = null,
        )
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        context.enableKnowledge(context.world,
            KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        return context
    }

    @Test
    fun nm01_empty_knowledge_pairs_share_planning_but_truth_rejects_only_the_colliding_candidate() {
        val empty = collisionContext(false)
        try {
            val hidden = collisionContext(true)
            try {
                val target = Position(250.0, 110.0)
                for (context in listOf(empty, hidden)) {
                    val accepted = assertIs<ActionResult.Accepted>(
                        context.submit(MoveAction("move", "p1", "u1", target, context.world)))
                    assertEquals(listOf("movement-started"), accepted.events.map { it.kind })
                    assertEquals(listOf(target), context.observe().units.single().pathPoints)
                }
                val firstEmpty = empty.advance(Step(0, 1.0, "sim-tick"))
                val firstHidden = hidden.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(firstEmpty.units, firstHidden.units)
                assertEquals(Position(130.0, 110.0), firstEmpty.units.single().position)
                assertEquals(20.0, firstEmpty.units.single().speed)
                assertEquals(0.0, firstEmpty.units.single().angularVelocity)
                assertEquals(listOf("movement-started"), firstEmpty.events.map { it.kind })
                assertEquals(listOf("movement-started"), firstHidden.events.map { it.kind })

                // Radius 11: the next candidate is 10 from the hidden rectangle's top edge.
                val secondEmpty = empty.advance(Step(1, 1.0, "sim-tick"))
                val secondHidden = hidden.advance(Step(1, 1.0, "sim-tick"))
                val moving = secondEmpty.units.single()
                val stopped = secondHidden.units.single()
                assertEquals(Position(150.0, 110.0), moving.position)
                assertEquals(20.0, moving.speed)
                assertEquals(0.0, moving.angularVelocity)
                assertEquals(MovementState.MOVING, moving.movementState)
                assertTrue(secondEmpty.events.isEmpty())
                assertEquals(Position(130.0, 110.0), stopped.position)
                assertEquals(0.0, stopped.speed)
                assertEquals(0.0, stopped.angularVelocity)
                assertEquals(MovementState.BLOCKED, stopped.movementState)
                assertEquals(listOf("movement-blocked"), secondHidden.events.map { it.kind })
                assertEquals(listOf(target), moving.pathPoints)
                assertEquals(moving.pathPoints, stopped.pathPoints)
                assertEquals(target, stopped.moveTarget)
            } finally { hidden.close() }
        } finally { empty.close() }
    }

    @Test
    fun nm02_negative_half_turn_obeys_the_existing_twenty_one_step_angular_limits() {
        val scenario = TestContentFactory.openScenario().copy(automaticVision = null)
        val movement = scenario.unitDefinitions.getValue("tank").movement
        assertEquals(4.1, movement.maxTurnSpeed)
        assertEquals(0.25, movement.turnAcceleration)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            val target = Position(100.0, 140.0)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("turn", "p1", "u1", target, context.world)))
            assertEquals(listOf(target), context.observe().units.single().pathPoints)
            // Twentieths of a degree: 16 quarter-degree accelerations, then five 4.1-degree turns.
            val turnUnits = listOf(5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80,
                82, 82, 82, 82, 82)
            assertEquals(1090, turnUnits.sum())
            var totalUnits = 0
            var previousOmega = 0.0
            for ((index, magnitude) in turnUnits.withIndex()) {
                totalUnits += magnitude
                val unit = context.advance(Step(index.toLong(), 1.0, "sim-tick")).units.single()
                val expectedOmega = -magnitude / 20.0
                assertEquals(Position(100.0, 100.0), unit.position)
                assertEquals(0.0, unit.speed)
                assertEquals(expectedOmega, unit.angularVelocity, 1e-9)
                assertEquals(360.0 - totalUnits / 20.0, unit.heading, 1e-9)
                assertTrue(abs(unit.angularVelocity) <= 4.1 + 1e-9)
                assertTrue(abs(unit.angularVelocity - previousOmega) <= 0.25 + 1e-9)
                previousOmega = unit.angularVelocity
            }
            assertEquals(305.5, context.observe().units.single().heading, 1e-9)
        } finally { context.close() }
    }
}
