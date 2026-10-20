package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.awt.geom.Line2D
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

class PrivateKnowledgeScenarioIntegrationTest {
    private fun scenario(): Scenario = ControlledScenario.load(
        Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).resolveSibling("P002-知识沙盒输入"))

    @Test fun sc05_real_private_content_exposes_allied_vision_but_never_control() {
        val input = scenario()
        assertEquals(600, input.map.groundGids.size)
        assertEquals(33, input.map.blocking.blockedCount)
        assertEquals(3, input.unitDefinitions.getValue("tank").fogOfWarSightRange)
        for (mode in SandboxMode.entries) for (shared in listOf(false, true)) {
            SandboxSession(input, options = SandboxOptions(mode, shared)).use { session ->
                val reveal = mode == SandboxMode.NO_FOG || shared
                assertEquals(if (reveal) setOf("u1", "u2", "u3") else setOf("u1"),
                    session.observation.units.map { it.unitId }.toSet(), "$mode shared=$shared")
                assertEquals(reveal, session.frame.terrain[15 * 30 + 27].known)
                session.select(Position(510.0, 310.0))
                if (reveal) {
                    assertEquals("u2", session.selectedId)
                    val before = session.observation.units
                    assertEquals("not-owner", assertIs<ActionResult.Rejected>(session.move(Position(510.0, 270.0))).error.code)
                    assertEquals("not-owner", assertIs<ActionResult.Rejected>(session.stop()).error.code)
                    assertEquals(before, session.observation.units)
                } else assertNull(session.selectedId)
            }
        }
    }

    @Test fun sc06_real_loaded_terrain_is_remembered_or_forgotten_after_moving_out_of_sight() {
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            SandboxSession(scenario(), options = SandboxOptions(mode)).use { session ->
                val initial = session.frame
                assertTrue(initial.terrain[5 * 30 + 5].visible)
                session.select(Position(110.0, 110.0))
                assertIs<ActionResult.Accepted>(session.move(Position(110.0, 290.0)))
                repeat(120) { session.tick() }
                val lost = session.frame.terrain[5 * 30 + 5]
                assertFalse(lost.visible)
                assertEquals(mode == SandboxMode.MEMORY, lost.known)
                assertEquals(if (mode == SandboxMode.MEMORY) initial.terrain[5 * 30 + 5].gid else null, lost.gid)
                assertTrue(initial.terrain[5 * 30 + 5].visible)
            }
        }
    }

    @Test fun sc07_one_move_on_real_loaded_map_discovers_the_wall_and_arrives_by_a_clear_route() {
        val input = scenario()
        val radius = input.unitDefinitions.getValue("tank").radius
        for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
            SandboxSession(input, options = SandboxOptions(mode)).use { session ->
                val wall = 5 * 30 + 14
                assertFalse(session.frame.terrain[wall].known)
                session.select(Position(110.0, 110.0))
                assertIs<ActionResult.Accepted>(session.move(Position(350.0, 110.0)))
                assertEquals(listOf(Position(350.0, 110.0)), session.observation.units.single().pathPoints)
                var discovered = false
                var rerouted = false
                var arrived = false
                var previous = session.observation.units.single().position
                println("P002_TRACE\tmode\tstep\tx\ty\theading\tspeed\tstate\twallVisible\tpath")
                for (step in 0 until 2500) {
                    session.tick()
                    val unit = session.observation.units.single()
                    val visible = session.frame.terrain[wall].visible
                    discovered = discovered || visible
                    rerouted = rerouted || unit.pathPoints.any { it.y != 110.0 }
                    println("P002_TRACE\t$mode\t$step\t${unit.position.x}\t${unit.position.y}\t${unit.heading}\t${unit.speed}\t${unit.movementState}\t$visible\t${unit.pathPoints.joinToString(";") { "${it.x}:${it.y}" }}")
                    for (row in 0 until 20) for (column in 0 until 30) if (input.map.blocking.isBlocked(column, row)) {
                        assertTrue(segmentRectangleDistance(previous, unit.position, column * 20.0, row * 20.0) > radius,
                            "$mode step=$step wall=($column,$row)")
                    }
                    previous = unit.position
                    if (unit.moveTarget == null) {
                        assertTrue(session.observation.events.any { it.kind == "movement-arrived" })
                        assertTrue(hypot(unit.position.x - 350.0, unit.position.y - 110.0) <= 4.0)
                        arrived = true
                        break
                    }
                }
                assertTrue(discovered, "$mode must discover the initially hidden wall")
                assertTrue(rerouted, "$mode must route away from the initial straight line")
                assertTrue(arrived, "$mode must arrive within the fixed 2500-step budget")
            }
        }
    }

    private fun segmentRectangleDistance(a: Position, b: Position, left: Double, top: Double): Double {
        val right = left + 20.0
        val bottom = top + 20.0
        fun pointDistance(p: Position) = hypot(maxOf(left - p.x, 0.0, p.x - right), maxOf(top - p.y, 0.0, p.y - bottom))
        val corners = listOf(Position(left, top), Position(right, top), Position(right, bottom), Position(left, bottom))
        if (corners.indices.any { index ->
                val c = corners[index]
                val d = corners[(index + 1) % 4]
                Line2D.linesIntersect(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y)
            }) return 0.0
        return minOf(pointDistance(a), pointDistance(b), corners.minOf {
            Line2D.ptSegDist(a.x, a.y, b.x, b.y, it.x, it.y)
        })
    }
}
