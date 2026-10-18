package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.path.PathSearchReason
import java.nio.file.Path
import kotlin.test.*

class KnowledgeReplanningTest {
    private val straight = listOf(Position(50.0, 50.0), Position(70.0, 50.0))
    private val upper = listOf(Position(30.0, 30.0), Position(50.0, 30.0), Position(70.0, 30.0), Position(70.0, 50.0))
    private fun scenario(vararg blocked: Pair<Int, Int>): Scenario {
        val cells = BooleanArray(25)
        blocked.forEach { (c, r) -> cells[r * 5 + c] = true }
        val unit = UnitDefinition("tank", "CustomUnitMetadata", 1.0, 1.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.LAND, 1.1, 0.07, 0.17, 4.1, 0.25, false, false), emptySet())
        return Scenario("rr-test", MapDefinition(5, 5, 20, 20, emptyList(), IntArray(25), BlockingGrid(5, 5, cells)),
            mapOf("tank" to unit), listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            listOf(UnitSpawn("u1", "p1", "tank", Position(30.0, 50.0), 0.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))
    }
    private fun open(input: Scenario = scenario(2 to 2), retention: TerrainRetention = TerrainRetention.MEMORY): SimulationContext =
        assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context.also {
            it.enableKnowledge(it.world, KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
        }
    private fun move(c: SimulationContext, id: String = "m1", at: Long? = null): ActionResult =
        c.submit(MoveAction(id, "p1", "u1", Position(70.0, 50.0), c.world, at))

    @Test
    fun rr02_other_owner_offpath_and_repeated_projection_do_not_search() {
        val c = open()
        try {
            c.enablePathDiagnostics(c.world)
            move(c)
            c.drainPathDiagnostics(c.world)
            c.acceptVisibleTerrain(c.world, "p2", listOf(VisibleTerrainCell(2, 2, true)))
            c.acceptVisibleTerrain(c.world, "p1", listOf(VisibleTerrainCell(0, 0, true)))
            c.advance(Step(0, 1.0, "sim-tick"))
            assertTrue(c.drainPathDiagnostics(c.world).isEmpty())
            assertEquals(straight, c.observe().units.single().pathPoints)
            c.acceptVisibleTerrain(c.world, "p1", listOf(VisibleTerrainCell(2, 2, true)))
            c.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(1, c.drainPathDiagnostics(c.world).size)
            c.acceptVisibleTerrain(c.world, "p1", listOf(VisibleTerrainCell(2, 2, true)))
            c.advance(Step(2, 1.0, "sim-tick"))
            assertTrue(c.drainPathDiagnostics(c.world).isEmpty())
        } finally { c.close() }
    }

    @Test
    fun rr03_memory_keeps_discovery_but_forgotten_discovery_does_not_replan() {
        for (retention in TerrainRetention.entries) {
            val c = open(retention = retention)
            try {
                c.enablePathDiagnostics(c.world)
                move(c)
                c.drainPathDiagnostics(c.world)
                c.acceptVisibleTerrain(c.world, "p1", listOf(VisibleTerrainCell(2, 2, true)))
                c.acceptVisibleTerrain(c.world, "p1", emptyList())
                val o = c.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(if (retention == TerrainRetention.MEMORY) upper else straight, o.units.single().pathPoints)
                assertEquals(if (retention == TerrainRetention.MEMORY) 1 else 0, c.drainPathDiagnostics(c.world).size)
                c.acceptVisibleTerrain(c.world, "p1", emptyList())
                c.advance(Step(1, 1.0, "sim-tick"))
                assertTrue(c.drainPathDiagnostics(c.world).isEmpty())
            } finally { c.close() }
        }
    }

    @Test
    fun rr04_true_collision_preserves_target_then_discovery_restores_movement() {
        val c = open()
        try {
            c.enablePathDiagnostics(c.world)
            move(c)
            c.drainPathDiagnostics(c.world)
            var blockedStep: Long? = null
            for (step in 0L until 200L) {
                val u = c.advance(Step(step, 1.0, "sim-tick")).units.single()
                val dx = u.position.x - u.position.x.coerceIn(40.0, 60.0)
                val dy = u.position.y - u.position.y.coerceIn(40.0, 60.0)
                assertTrue(dx * dx + dy * dy > 1.0)
                if (u.movementState == MovementState.BLOCKED) { blockedStep = step; break }
            }
            assertNotNull(blockedStep)
            assertEquals(Position(70.0, 50.0), c.observe().units.single().moveTarget)
            assertTrue(c.observe().units.single().pathPoints.isNotEmpty())
            c.acceptVisibleTerrain(c.world, "p1", listOf(VisibleTerrainCell(2, 2, true)))
            val o = c.advance(Step(blockedStep + 1, 1.0, "sim-tick"))
            assertEquals(MovementState.MOVING, o.units.single().movementState)
            assertEquals(Position(70.0, 50.0), o.units.single().moveTarget)
            assertEquals(1, c.drainPathDiagnostics(c.world).size)
            assertTrue(o.actionResults.isEmpty())
        } finally { c.close() }
    }

    @Test
    fun rr05_full_wall_cancels_once_and_later_projection_does_not_revive() {
        val c = open(scenario(2 to 0, 2 to 1, 2 to 2, 2 to 3, 2 to 4))
        try {
            c.enablePathDiagnostics(c.world)
            move(c)
            c.drainPathDiagnostics(c.world)
            c.acceptVisibleTerrain(c.world, "p1", (0..4).map { VisibleTerrainCell(2, it, true) })
            val o = c.advance(Step(0, 1.0, "sim-tick"))
            val u = o.units.single()
            assertNull(u.moveTarget)
            assertTrue(u.pathPoints.isEmpty())
            assertEquals(MovementState.IDLE, u.movementState)
            assertEquals(listOf("movement-started", "movement-cancelled", "path-unreachable"), o.events.map { it.kind })
            assertEquals(listOf("m1"), o.actionResults.map { it.actionId })
            assertEquals(1, c.drainPathDiagnostics(c.world).size)
            c.acceptVisibleTerrain(c.world, "p1", listOf(VisibleTerrainCell(1, 0, true)))
            val later = c.advance(Step(1, 1.0, "sim-tick"))
            assertNull(later.units.single().moveTarget)
            assertTrue(later.events.isEmpty())
            assertTrue(c.drainPathDiagnostics(c.world).isEmpty())
        } finally { c.close() }
    }

    @Test
    fun rr06_new_immediate_or_scheduled_move_uses_latest_revision_and_stop_wins() {
        for (mode in listOf("immediate", "scheduled", "stop")) {
            val c = open()
            try {
                c.enablePathDiagnostics(c.world)
                move(c)
                c.drainPathDiagnostics(c.world)
                c.acceptVisibleTerrain(c.world, "p1", listOf(VisibleTerrainCell(2, 2, true)))
                when (mode) {
                    "immediate" -> { move(c, "new"); assertEquals(1, c.drainPathDiagnostics(c.world).size) }
                    "scheduled" -> move(c, "new", 0)
                    else -> c.submit(StopAction("stop", "p1", "u1", c.world, 0))
                }
                val o = c.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(if (mode == "scheduled") 1 else 0, c.drainPathDiagnostics(c.world).size)
                if (mode == "stop") assertNull(o.units.single().moveTarget)
                else assertEquals(upper, o.units.single().pathPoints)
            } finally { c.close() }
        }
    }

    @Test
    fun rr07_batch_units_invalid_projection_and_reopen_keep_pending_state_scoped() {
        val input = scenario(2 to 2).let { it.copy(units = it.units + UnitSpawn("u2", "p1", "tank", Position(30.0, 90.0), 0.0)) }
        val c = open(input)
        try {
            c.enablePathDiagnostics(c.world)
            move(c)
            c.submit(MoveAction("m2", "p1", "u2", Position(70.0, 90.0), c.world))
            c.drainPathDiagnostics(c.world)
            assertFailsWith<KnowledgeException> { c.acceptVisibleTerrain(c.world, "p1", listOf(VisibleTerrainCell(2, 2, true), VisibleTerrainCell(2, 2, true))) }
            assertEquals(0L, c.knowledgeSnapshot(c.world, "p1").revision)
            c.advance(Step(0, 1.0, "sim-tick"))
            assertTrue(c.drainPathDiagnostics(c.world).isEmpty())
            c.acceptVisibleTerrain(c.world, "p1", listOf(VisibleTerrainCell(2, 2, true), VisibleTerrainCell(0, 0, true)))
            c.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(listOf("u1"), c.drainPathDiagnostics(c.world).map { it.unitId })
            val old = c.world
            c.acceptVisibleTerrain(old, "p1", listOf(VisibleTerrainCell(2, 4, true)))
            c.close()
            assertIs<InitResult.Accepted>(c.reopen(input))
            c.enableKnowledge(c.world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            c.enablePathDiagnostics(c.world)
            assertFailsWith<KnowledgeException> { c.acceptVisibleTerrain(old, "p1", emptyList()) }
            move(c, "newworld")
            c.drainPathDiagnostics(c.world)
            c.advance(Step(0, 1.0, "sim-tick"))
            assertTrue(c.drainPathDiagnostics(c.world).isEmpty())
        } finally { c.close() }
    }

    @Test
    fun rr08_arithmetic_rollback_preserves_discoveries_and_attempt_samples() {
        val base = scenario()
        val tank = base.unitDefinitions.getValue("tank")
        val fast = UnitDefinition("fast", tank.className, 1.0, 1.0, 1, tank.imagePath, 1.0,
            tank.movement.copy(moveSpeed = Double.MAX_VALUE, moveAccelerationSpeed = Double.MAX_VALUE, moveDecelerationSpeed = Double.MAX_VALUE), emptySet())
        val c = open(base.copy(unitDefinitions = mapOf("tank" to tank, "fast" to fast), units = listOf(
            UnitSpawn("a", "p1", "tank", Position(30.0, 50.0), 0.0), UnitSpawn("z", "p1", "fast", Position(30.0, 10.0), 90.0))))
        try {
            c.enablePathDiagnostics(c.world)
            c.submit(MoveAction("normal", "p1", "a", Position(70.0, 50.0), c.world))
            c.submit(MoveAction("fast", "p1", "z", Position(70.0, 10.0), c.world))
            c.advance(Step(0, 1.0, "sim-tick"))
            c.drainPathDiagnostics(c.world)
            c.acceptVisibleTerrain(c.world, "p1", listOf(VisibleTerrainCell(2, 2, true)))
            val previous = c.observe()
            assertFailsWith<ArithmeticException> { c.advance(Step(1, 1.0, "sim-tick")) }
            assertSame(previous, c.observe())
            assertEquals(1L, c.knowledgeSnapshot(c.world, "p1").revision)
            assertEquals(listOf("a"), c.drainPathDiagnostics(c.world).map { it.unitId })
            c.submit(StopAction("stop-fast", "p1", "z", c.world))
            c.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(listOf("a"), c.drainPathDiagnostics(c.world).map { it.unitId })
            assertEquals(Position(70.0, 50.0), c.observe().units.first { it.unitId == "a" }.moveTarget)
        } finally { c.close() }
    }

    @Test
    fun rr09_tangent_segment_and_rounded_corner_use_exact_distance() {
        val base = scenario()
        val source = base.unitDefinitions.getValue("tank")
        fun unit(radius: Double) = UnitDefinition("tank", source.className, radius, radius, 1, source.imagePath, 1.0, source.movement, emptySet())
        for (radius in listOf(9.0, 10.0)) {
            val c = open(base.copy(unitDefinitions = mapOf("tank" to unit(radius))))
            try {
                c.enablePathDiagnostics(c.world)
                move(c)
                c.drainPathDiagnostics(c.world)
                c.acceptVisibleTerrain(c.world, "p1", listOf(VisibleTerrainCell(2, 1, true)))
                c.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(if (radius == 10.0) 1 else 0, c.drainPathDiagnostics(c.world).size)
            } finally { c.close() }
        }
        // (39,41) is inside the expanded box but sqrt(2) from closed corner(40,40), outside radius1.
        val corner = open(base.copy(units = listOf(UnitSpawn("u1", "p1", "tank", Position(39.0, 41.0), 0.0))))
        try {
            corner.enablePathDiagnostics(corner.world)
            corner.submit(MoveAction("corner", "p1", "u1", Position(30.0, 50.0), corner.world))
            corner.drainPathDiagnostics(corner.world)
            corner.acceptVisibleTerrain(corner.world, "p1", listOf(VisibleTerrainCell(2, 1, true)))
            corner.advance(Step(0, 1.0, "sim-tick"))
            assertTrue(corner.drainPathDiagnostics(corner.world).isEmpty())
        } finally { corner.close() }
    }

    @Test
    fun rr10_reason_execution_step_origin_and_diagnostics_off_preserve_gameplay() {
        val plain = open()
        val measured = open()
        try {
            measured.enablePathDiagnostics(measured.world)
            for (c in listOf(plain, measured)) move(c, "scheduled", 0)
            plain.advance(Step(0, 1.0, "sim-tick"))
            measured.advance(Step(0, 1.0, "sim-tick"))
            val explicit = measured.drainPathDiagnostics(measured.world).single()
            assertEquals(PathSearchReason.USER_MOVE, explicit.reason)
            assertEquals(0L, explicit.executionStep)
            assertEquals(0L, explicit.scheduledStep)
            for (c in listOf(plain, measured)) c.acceptVisibleTerrain(c.world, "p1", listOf(VisibleTerrainCell(2, 2, true)))
            val a = plain.advance(Step(1, 1.0, "sim-tick"))
            val b = measured.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(a.units, b.units)
            assertEquals(a.events, b.events)
            assertEquals(a.actionResults, b.actionResults)
            assertEquals(a.sampleId, b.sampleId)
            assertEquals(a.simulationTime, b.simulationTime)
            val auto = measured.drainPathDiagnostics(measured.world).single()
            assertEquals(PathSearchReason.KNOWLEDGE_REPLAN, auto.reason)
            assertEquals("scheduled", auto.actionId)
            assertEquals(0L, auto.scheduledStep)
            assertEquals(1L, auto.executionStep)
            assertEquals(1L, auto.actualSearchCalls)
            assertTrue(b.actionResults.isEmpty())
            move(measured, "immediate")
            val immediate = measured.drainPathDiagnostics(measured.world).single()
            assertEquals(PathSearchReason.USER_MOVE, immediate.reason)
            assertNull(immediate.executionStep)
            assertNull(immediate.scheduledStep)
        } finally { plain.close(); measured.close() }
    }

    @Test
    fun rr01_owner_discovery_replans_on_advance_without_another_move() {
        val c = open()
        try {
            c.enablePathDiagnostics(c.world)
            assertIs<ActionResult.Accepted>(move(c))
            assertEquals(straight, c.observe().units.single().pathPoints)
            c.drainPathDiagnostics(c.world)
            c.acceptVisibleTerrain(c.world, "p1", listOf(VisibleTerrainCell(2, 2, true)))
            assertTrue(c.drainPathDiagnostics(c.world).isEmpty())
            val observation = c.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(upper, observation.units.single().pathPoints)
            assertEquals(Position(70.0, 50.0), observation.units.single().moveTarget)
            assertEquals(listOf("m1"), observation.actionResults.map { it.actionId })
            assertEquals(1L, c.drainPathDiagnostics(c.world).single().actualSearchCalls)
        } finally { c.close() }
    }
}
