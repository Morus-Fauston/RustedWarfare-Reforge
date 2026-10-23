package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.math.sqrt
import kotlin.test.*

class CenterTerrainMovementTest {
    private fun open(start: Position, heading: Double, walls: Set<Pair<Int, Int>>,
                     speed: Double = 1.0, hidden: Boolean = false, radius: Double = 11.0): SimulationContext {
        val source = TestContentFactory.openScenario()
        val tank = source.unitDefinitions.getValue("tank")
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600),
                BlockingGrid(30, 20, BooleanArray(600) { (it % 30 to it / 30) in walls })),
            unitDefinitions = mapOf("tank" to TestContentFactory.unitWith(tank, radius = radius, movement = tank.movement.copy(
                moveSpeed = speed, moveAccelerationSpeed = 1.0, moveDecelerationSpeed = 1.0))),
            units = listOf(UnitSpawn("u1", "p1", "tank", start, heading)), automaticVision = null,
        ))).context
        if (hidden) context.enableKnowledge(context.world, KnowledgeRules(
            TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        return context
    }

    @Test fun terrain_correction_cannot_push_the_circle_outside_the_retained_map_boundary() {
        val start = Position(580.1, 200.0)
        val context = open(start, 270.0, setOf(28 to 10), speed = 0.2, hidden = true, radius = 19.9)
        try {
            context.submit(MoveAction("west", "p1", "u1", Position(40.0, 200.0), context.world))
            val observation = context.advance(Step(0, 1.0, "sim-tick"))
            val unit = observation.units.single()
            assertEquals(start, unit.position) // Candidate579.9 is legal; corrected580.2 is not.
            assertEquals(0.0, unit.speed)
            assertNull(unit.moveTarget)
            assertTrue(unit.pathPoints.isEmpty())
            assertEquals(MovementState.BLOCKED, unit.movementState)
            assertEquals(listOf("movement-started", "movement-boundary-blocked"), observation.events.map { it.kind })
        } finally { context.close() }
    }

    @Test fun wall_correction_retains_propulsion_and_command_instead_of_circle_rejection() {
        val context = open(Position(279.5, 200.0), 90.0,
            (6..13).flatMap { y -> (14..15).map { x -> x to y } }.toSet(), hidden = true)
        try {
            context.submit(MoveAction("move", "p1", "u1", Position(500.0, 200.0), context.world))
            repeat(2) {
                val observation = context.advance(Step(it.toLong(), 1.0, "sim-tick"))
                val unit = observation.units.single()
                assertEquals(279.8, unit.position.x, 1e-9)
                assertEquals(200.0, unit.position.y, 1e-9)
                assertEquals(1.0, unit.speed)
                assertEquals(Position(500.0, 200.0), unit.moveTarget)
                assertTrue(observation.events.none { e -> e.kind in setOf("movement-arrived", "path-unreachable") })
            }
        } finally { context.close() }
    }

    @Test fun corner_x_edge_overrides_y_and_two_blocked_side_cells_preserve_old_position() {
        data class Example(val start: Position, val walls: Set<Pair<Int, Int>>, val expected: Position, val speed: Double)
        for ((start, walls, expected, speed) in listOf(
            // These tile coordinates are exact binary fractions. No epsilon is
            // introduced to turn a rounded corner miss into an intersection.
            Example(Position(277.5, 117.5), setOf(14 to 6), Position(279.8, 122.5), sqrt(50.0)),
            Example(Position(19.5, 19.5), setOf(0 to 1, 1 to 0, 1 to 1), Position(19.5, 19.5), sqrt(2.0)),
        )) {
            val context = open(start, 135.0, walls, speed, hidden = true)
            try {
                // A far diagonal target that keeps the existing 135 degree heading for this step.
                context.submit(MoveAction("diagonal", "p1", "u1", Position(start.x + 100, start.y + 100), context.world))
                val unit = context.advance(Step(0, 1.0, "sim-tick")).units.single()
                assertEquals(expected.x, unit.position.x, 1e-9)
                assertEquals(expected.y, unit.position.y, 1e-9)
                assertEquals(speed, unit.speed)
                assertNotNull(unit.moveTarget)
            } finally { context.close() }
        }
    }

    @Test fun radius_eleven_can_plan_through_a_twenty_unit_corridor() {
        val walls = (4..15).flatMap { row -> listOf(13 to row, 15 to row) }.toSet()
        val context = open(Position(290.0, 110.0), 180.0, walls)
        try {
            context.submit(MoveAction("move", "p1", "u1", Position(290.0, 270.0), context.world))
            assertEquals(listOf(Position(290.0, 270.0)), context.observe().units.single().pathPoints)
            val samples = (0L..180L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            assertTrue(samples.all { it.units.single().position.x == 290.0 })
            assertEquals(1, samples.sumOf { s -> s.events.count { it.kind == "movement-arrived" } })
            assertEquals(MovementState.IDLE, samples.last().units.single().movementState)
            assertTrue(samples.takeLast(18).all { it.units == samples.last().units })
        } finally { context.close() }
    }

    @Test fun exact_open_right_and_bottom_edges_allow_departure_while_closed_edges_are_blocked() {
        for ((start, goal, heading) in listOf(
            Triple(Position(300.0, 130.0), Position(310.0, 130.0), 90.0),
            Triple(Position(290.0, 140.0), Position(290.0, 150.0), 180.0),
        )) {
            val context = open(start, heading, setOf(14 to 6))
            try {
                val result = assertIs<ActionResult.Accepted>(context.submit(MoveAction("depart", "p1", "u1", goal, context.world)))
                assertTrue(result.events.none { it.kind == "path-unreachable" })
                assertEquals(listOf(goal), context.observe().units.single().pathPoints)
                assertNotEquals(start, context.advance(Step(0, 1.0, "sim-tick")).units.single().position)
            } finally { context.close() }
        }
    }
}
