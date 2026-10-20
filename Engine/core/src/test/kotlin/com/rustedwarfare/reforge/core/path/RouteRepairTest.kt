package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import java.awt.geom.Line2D
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

class RouteRepairTest {
    // D-083: move the repair geometry into the interior; do not rely on illegal edge candidates.
    private val target = Position(450.0, 210.0)
    private val walls = (7..12).map { 19 to it }

    private fun open(hidden: Boolean = false, truthWalls: Boolean = true, diagnostics: Boolean = true,
                     known: Boolean = false): SimulationContext {
        val unit = UnitDefinition("tank", "CustomUnitMetadata", 11.0, 11.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.LAND, 1.1, 0.07, 0.17, 4.1, 0.25, false, false), emptySet())
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(Scenario("route-repair",
            MapDefinition(40, 30, 20, 20, emptyList(), IntArray(1200), BlockingGrid(40, 30,
                BooleanArray(1200) { truthWalls && it % 40 == 19 && it / 40 in 7..12 })), mapOf("tank" to unit),
            listOf(Player("p1", PlayerControl.LOCAL)),
            listOf(UnitSpawn("u1", "p1", "tank", Position(360.80333715352117, 198.0989131273078), 128.3558160425307)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0)))).context
        if (hidden || known) context.enableKnowledge(context.world,
            KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        if (known) context.acceptVisibleTerrain(context.world, "p1", walls.map { (column, row) ->
            VisibleTerrainCell(column, row, true)
        })
        if (diagnostics) context.enablePathDiagnostics(context.world)
        return context
    }

    @Test fun rp02_unknown_truth_never_triggers_repair_or_teaches_terrain() {
        val blocked = open(hidden = true)
        val empty = open(hidden = true, truthWalls = false)
        try {
            for (context in listOf(blocked, empty)) {
                context.submit(MoveAction("hidden-move", "p1", "u1", target, context.world))
                assertEquals(listOf(target), context.observe().units.single().pathPoints)
                assertTrue(context.drainPathDiagnostics(context.world).single().usedPlayerKnowledge)
            }
            var collision = false
            for (step in 0L until 120L) {
                val a = blocked.advance(Step(step, 1.0, "sim-tick")).units.single()
                val b = empty.advance(Step(step, 1.0, "sim-tick")).units.single()
                collision = collision || a.movementState == MovementState.BLOCKED
                assertTrue(blocked.drainPathDiagnostics(blocked.world).isEmpty())
                assertTrue(empty.drainPathDiagnostics(empty.world).isEmpty())
                assertEquals(blocked.knowledgeSnapshot(blocked.world, "p1").cells,
                    empty.knowledgeSnapshot(empty.world, "p1").cells)
                if (!collision) assertEquals(a, b)
                assertEquals(target, a.moveTarget)
                assertEquals(listOf(target), a.pathPoints)
            }
            assertTrue(collision, "Hidden truth must still physically stop the moving unit")
        } finally { blocked.close(); empty.close() }
    }

    @Test fun rp03_diagnostics_do_not_change_repaired_motion_and_scheduled_origin_survives() {
        val measured = open()
        val plain = open(diagnostics = false)
        try {
            for (context in listOf(measured, plain)) {
                context.submit(MoveAction("scheduled-origin", "p1", "u1", target, context.world, atStep = 0))
            }
            var repairs = 0
            var arrived = false
            for (step in 0L until 1500L) {
                val a = measured.advance(Step(step, 1.0, "sim-tick"))
                val b = plain.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(a.sampleId, b.sampleId)
                assertEquals(a.worldId, b.worldId)
                assertEquals(a.scenarioId, b.scenarioId)
                assertEquals(a.stepIndex, b.stepIndex)
                assertEquals(a.simulationTime, b.simulationTime)
                assertEquals(a.units, b.units)
                assertEquals(a.events, b.events)
                assertEquals(a.actionResults, b.actionResults)
                val samples = measured.drainPathDiagnostics(measured.world)
                assertTrue(samples.size <= 1)
                for (sample in samples) {
                    assertEquals("scheduled-origin", sample.actionId)
                    assertEquals(0L, sample.scheduledStep)
                    assertEquals(step, sample.executionStep)
                    if (sample.reason == PathSearchReason.ROUTE_REPAIR) repairs++
                    else assertEquals(0L, step)
                }
                if (a.units.single().moveTarget == null) {
                    assertTrue(a.events.any { it.kind == "movement-arrived" })
                    arrived = true
                    break
                }
            }
            assertTrue(repairs > 0)
            assertTrue(arrived)
        } finally { measured.close(); plain.close() }
    }

    @Test fun rp04_scheduled_stop_wins_over_an_invalid_remaining_connection() {
        val context = open()
        try {
            context.submit(MoveAction("stopped-origin", "p1", "u1", target, context.world))
            context.drainPathDiagnostics(context.world)
            var stopped = false
            for (step in 0L until 1500L) {
                val before = context.observe().units.single()
                if (before.pathPoints.isNotEmpty() && !clear(before.position, before.pathPoints.first())) {
                    context.submit(StopAction("scheduled-stop", "p1", "u1", context.world, atStep = step))
                    val after = context.advance(Step(step, 1.0, "sim-tick"))
                    assertNull(after.units.single().moveTarget)
                    assertTrue(after.units.single().pathPoints.isEmpty())
                    assertTrue(after.events.any { it.kind == "movement-cancelled" })
                    assertTrue(context.drainPathDiagnostics(context.world).isEmpty())
                    repeat(20) { offset ->
                        assertNull(context.advance(Step(step + offset + 1, 1.0, "sim-tick")).units.single().moveTarget)
                        assertTrue(context.drainPathDiagnostics(context.world).isEmpty())
                    }
                    stopped = true
                    break
                }
                context.advance(Step(step, 1.0, "sim-tick"))
                context.drainPathDiagnostics(context.world)
            }
            assertTrue(stopped, "The stop must run exactly when a real connection has become unsafe")
        } finally { context.close() }
    }

    @Test fun rp05_known_candidate_brakes_translation_while_turning_and_arrives() {
        val context = open(known = true)
        try {
            context.submit(MoveAction("known-origin", "p1", "u1", target, context.world))
            val initial = context.observe().units.single()
            assertTrue((listOf(initial.position) + initial.pathPoints).zipWithNext().all { (a, b) -> clear(a, b) })
            assertTrue(context.drainPathDiagnostics(context.world).single().usedPlayerKnowledge)
            var turningInPlace = false
            var repairs = 0
            var arrived = false
            for (step in 0L until 1500L) {
                val before = context.observe().units.single()
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                val after = observation.units.single()
                assertTrue(clear(before.position, after.position), "step=$step unsafe swept movement")
                assertFalse(observation.events.any { it.kind == "movement-blocked" },
                    "step=$step an owner-known wall must be handled before the truth collision")
                if (before.position == after.position && after.speed == 0.0 && after.heading != before.heading &&
                    after.angularVelocity != 0.0) turningInPlace = true
                val samples = context.drainPathDiagnostics(context.world)
                assertTrue(samples.size <= 1)
                for (sample in samples) {
                    assertEquals(PathSearchReason.ROUTE_REPAIR, sample.reason)
                    assertEquals("known-origin", sample.actionId)
                    assertEquals(step, sample.executionStep)
                    assertTrue(sample.usedPlayerKnowledge)
                    repairs++
                }
                if (after.moveTarget == null) {
                    assertTrue(observation.events.any { it.kind == "movement-arrived" })
                    arrived = true
                    repeat(10) { offset ->
                        assertNull(context.advance(Step(step + offset + 1, 1.0, "sim-tick")).units.single().moveTarget)
                        assertTrue(context.drainPathDiagnostics(context.world).isEmpty())
                    }
                    break
                }
            }
            assertTrue(turningInPlace)
            assertTrue(repairs > 0)
            assertTrue(arrived)
        } finally { context.close() }
    }

    @Test fun rp01_curved_motion_repairs_a_now_unsafe_known_connection_without_a_new_discovery() {
        val context = open()
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("original-move", "p1", "u1", target, context.world)))
            val initial = context.observe().units.single()
            assertTrue((listOf(initial.position) + initial.pathPoints).zipWithNext().all { (a, b) -> clear(a, b) })
            assertEquals("USER_MOVE", context.drainPathDiagnostics(context.world).single().reason.name)
            var invalidConnection = false
            var repairs = 0
            var arrived = false
            for (step in 0L until 1500L) {
                val before = context.observe().units.single()
                if (before.pathPoints.isNotEmpty() && !clear(before.position, before.pathPoints.first())) invalidConnection = true
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                val unit = observation.units.single()
                assertTrue(clear(before.position, unit.position), "step=$step swept movement intersects a wall")
                val samples = context.drainPathDiagnostics(context.world)
                assertTrue(samples.size <= 1, "step=$step duplicate automatic searches")
                for (sample in samples) {
                    assertEquals("ROUTE_REPAIR", sample.reason.name)
                    assertEquals("original-move", sample.actionId)
                    assertEquals(step, sample.executionStep)
                    assertNull(sample.scheduledStep)
                    assertFalse(sample.usedPlayerKnowledge)
                    repairs++
                }
                if (samples.isNotEmpty() || unit.movementState == MovementState.BLOCKED) {
                    println("RP01,$step,${unit.position},${unit.heading},${unit.speed},${unit.pathPoints},$samples")
                }
                if (unit.moveTarget == null) {
                    assertTrue(observation.events.any { it.kind == "movement-arrived" })
                    arrived = true
                    break
                }
            }
            assertTrue(invalidConnection, "The fixture must exercise motion-induced connection invalidation")
            assertTrue(repairs > 0, "An unsafe known connection must trigger a route repair without a new projection")
            assertTrue(arrived, "The original Move must arrive within the fixed budget")
        } finally { context.close() }
    }

    @Test fun rp06_reconnect_keeps_the_safe_old_anchor_and_suffix_and_arrives_at_original_goal() {
        val context = open(known = true)
        try {
            context.submit(MoveAction("suffix-origin", "p1", "u1", target, context.world))
            context.drainPathDiagnostics(context.world)
            var reconnected = false
            var arrived = false
            for (step in 0L until 1500L) {
                val before = context.observe().units.single()
                val oldPath = before.pathPoints
                val safeSuffix = oldPath.size > 1 && oldPath.zipWithNext().all { (a, b) -> clear(a, b) }
                val reconnect = safeSuffix && !clear(before.position, oldPath.first())
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                val after = observation.units.single()
                assertTrue(clear(before.position, after.position), "step=$step swept movement")
                val samples = context.drainPathDiagnostics(context.world)
                if (reconnect) {
                    assertTrue(hypot(before.position.x - oldPath.first().x, before.position.y - oldPath.first().y) > 16.0,
                        "Fixture must not capture the old anchor in the repair step")
                    assertEquals(oldPath, after.pathPoints.takeLast(oldPath.size),
                        "step=$step reconnect must preserve the safe anchor and entire old suffix")
                    assertEquals(target, after.moveTarget)
                    assertEquals(PathSearchReason.ROUTE_REPAIR, samples.single().reason)
                    assertEquals("suffix-origin", samples.single().actionId)
                    assertEquals(step, samples.single().executionStep)
                    assertEquals(oldPath.first(), samples.single().searchTarget)
                    reconnected = true
                }
                if (after.moveTarget == null) {
                    assertTrue(observation.events.any { it.kind == "movement-arrived" })
                    arrived = true
                    break
                }
            }
            assertTrue(reconnected, "Fixture must actually invalidate a connection with a still-safe suffix")
            assertTrue(arrived, "Reconnect must reach the original goal within the unchanged budget")
        } finally { context.close() }
    }

    private fun clear(a: Position, b: Position): Boolean = walls.all { (column, row) ->
        val left = column * 20.0
        val top = row * 20.0
        val right = left + 20.0
        val bottom = top + 20.0
        fun distance(p: Position) = hypot(maxOf(left - p.x, 0.0, p.x - right), maxOf(top - p.y, 0.0, p.y - bottom))
        val corners = listOf(Position(left, top), Position(right, top), Position(right, bottom), Position(left, bottom))
        corners.indices.none { index ->
            val c = corners[index]
            val d = corners[(index + 1) % 4]
            Line2D.linesIntersect(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y)
        } && minOf(distance(a), distance(b), corners.minOf { Line2D.ptSegDist(a.x, a.y, b.x, b.y, it.x, it.y) }) > 11.0
    }
}
