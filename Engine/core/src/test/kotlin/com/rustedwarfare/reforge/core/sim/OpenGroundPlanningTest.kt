package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.path.FirstSegmentDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenGroundPlanningTest {
    private fun openingDecisions(farCount: Int = 0, spawnAt: Position? = null): List<FirstSegmentDecision> {
        val opening = TestContentFactory.openScenario()
        val tank = TestContentFactory.unitWith(opening.unitDefinitions.getValue("tank"), radius = 6.0)
        val map = MapDefinition(80, 60, 20, 20, emptyList(), IntArray(80 * 60),
            BlockingGrid(80, 60, BooleanArray(80 * 60) { it % 80 == 22 && it / 80 in 6..9 }))
        val drivers = listOf(UnitSpawn("lead", "p1", "tank", Position(90.0, 170.0), 90.0),
            UnitSpawn("other", "p1", "tank", Position(100.0, 460.0), 90.0))
        val far = List(farCount) { i -> UnitSpawn("far-$i", "p1", "tank",
            Position(820.0 + i % 24 * 20.0, 620.0 + i / 24 * 20.0), 0.0) }
        val scene = opening.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = drivers + far)
        val context = assertIs<InitResult.Accepted>(
            SimulationContext(ExperimentalPolicies(movePlanningBudgetPerStep = 64)).initialize(scene)).context
        try {
            val click = Position(650.0, 170.0)
            val ids = drivers.map { it.unitId }
            val group = assertNotNull(context.requestGroupMove("p1", ids, click))
            context.enablePlanningWorkDiagnostics(context.world)
            for (id in ids) assertIs<ActionResult.Accepted>(context.submitGroupMove(
                MoveAction("go-$id", "p1", id, click, context.world), group))
            val decisions = mutableListOf<FirstSegmentDecision>()
            for (tick in 0L..2000L) {
                val step = Step(tick, 1.0, "sim-tick")
                if (tick == 1L && spawnAt != null) {
                    val request = SandboxCommandRequest(1, context.world, 1, "spawn")
                    val (_, receipts) = context.advanceSandbox(step,
                        listOf(request to SandboxCommand.Spawn("tank", spawnAt, "p2")))
                    assertEquals(SandboxCommandStatus.APPLIED, receipts.single().status)
                } else context.advance(step)
                val work = context.drainPlanningWorkDiagnostics(context.world).single()
                assertTrue(work.used <= work.limit)
                if (tick == 0L) assertTrue(work.slices.any { "lead" in it.unitIds && !it.completed })
                decisions += work.slices.mapNotNull { it.firstSegment }
                if (ids.all { id -> decisions.any { it.unitId == id && it.outcome == "adopt" } }) return decisions
            }
            error("Drivers did not publish routes: $decisions")
        } finally { context.close() }
    }

    @Test
    fun a_newly_known_body_on_the_approach_invalidates_a_pending_open_ground_order() {
        val clear = openingDecisions()
        val leg = clear.first { it.unitId == "lead" }
        val middle = Position((leg.start.x + leg.end.x) / 2, (leg.start.y + leg.end.y) / 2)
        val blocked = openingDecisions(spawnAt = middle)
        assertTrue(blocked.any { it.unitId == "lead" && it.blockers.size == 1 && it.outcome == "replan-member" })
        val other = blocked.single { it.unitId == "other" }
        val unaffected = clear.single { it.unitId == "other" }
        assertEquals(unaffected.end, other.end)
        assertEquals(unaffected.outcome, other.outcome)
        assertTrue(other.blockers.isEmpty())
    }

    @Test
    fun a_known_distant_population_is_not_rescanned_for_every_open_ground_route() {
        val baseline = openingDecisions()
        val crowded = openingDecisions(farCount = 384)
        assertEquals(baseline, crowded)
        assertTrue(crowded.all { it.candidateCount == 0 })
    }

    @Test
    fun a_ready_driver_moves_under_its_own_order_while_the_tail_is_still_planning() {
        val opening = TestContentFactory.openScenario()
        val tank = TestContentFactory.unitWith(opening.unitDefinitions.getValue("tank"), radius = 6.0)
        val width = 48; val height = 32
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height),
            BlockingGrid(width, height, BooleanArray(width * height)))
        val click = Position(640.0, 240.0)
        val spawns = List(50) { i -> UnitSpawn("u${i.toString().padStart(2, '0')}", "p1", "tank",
            Position(80.0 + i % 10 * 32, 150.0 + i / 10 * 38), 90.0) }
        val scene = opening.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = spawns)
        val context = assertIs<InitResult.Accepted>(SimulationContext(ExperimentalPolicies(movePlanningBudgetPerStep = 16)).initialize(scene)).context
        try {
            val ids = spawns.map { it.unitId }.toSet()
            val group = assertNotNull(context.requestGroupMove("p1", ids, click))
            context.enableMotionDiagnostics(context.world)
            for (id in ids) assertIs<ActionResult.Accepted>(context.submitGroupMove(MoveAction("go-$id", "p1", id, click, context.world), group))
            val started = mutableMapOf<String, Long>()
            val own = mutableMapOf<String, Long>()
            for (tick in 0L..2000L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                for (event in frame.events.filter { it.kind == "movement-started" }) {
                    val id = assertNotNull(event.unitId)
                    assertTrue(id !in started, "Must not reissue $id after publishing it")
                    started[id] = tick
                }
                for (m in context.drainMotionDiagnostics(context.world)) if (m.originMoveId == "go-${m.unitId}" &&
                    m.commandTargetBefore != null && m.pushedDisplacement == Position(0.0, 0.0) &&
                    m.unitId in started && m.finalPosition.x > m.positionBefore.x) own.putIfAbsent(m.unitId, tick)
                if (own.size == ids.size) break
            }
            assertEquals(ids, started.keys, "Queue tail must receive routes")
            assertEquals(ids, own.keys, "A published route or a push alone is not a start")
            assertTrue(own.values.min() < started.values.max(),
                "First forward motion must precede last publication: firstOwn=${own.values.min()}, lastPublished=${started.values.max()}")
        } finally { context.close() }
    }
}
