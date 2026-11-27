package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroupMoveExpansionTest {
    @Test
    fun terminal_landing_blocks_throat_plugging_and_fans_queued_cars_into_open_wings() {
        val base = TestContentFactory.openScenario()
        val tank = TestContentFactory.unitWith(base.unitDefinitions.getValue("tank"), radius = 6.0)
        // Build a 1-tile throat at col=10, row=10 opening into a wide room on the right (cols 11..25).
        val width = 32; val height = 22
        val blocked = BooleanArray(width * height) { idx ->
            val c = idx % width; val r = idx / width
            c == 10 && r != 10
        }
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height),
            BlockingGrid(width, height, blocked))
        val spawns = (0 until 3).map { i ->
            UnitSpawn("c$i", "p1", "tank", Position(210.0 - i * 18.0, 210.0), 90.0)
        }
        val scene = base.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = spawns)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val click = Position(248.0, 210.0)
            val ids = spawns.map { it.unitId }
            val group = assertNotNull(context.requestGroupMove("p1", ids, click))
            ids.forEach { id ->
                assertIs<ActionResult.Accepted>(context.submitGroupMove(MoveAction("go-$id", "p1", id, click, context.world), group))
            }
            var settledFrame: Observation? = null
            for (step in 0L..1200L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                for (i in frame.units.indices) for (j in i + 1 until frame.units.size) {
                    assertTrue(ScreenPlane.distanceSquared(frame.units[i].position, frame.units[j].position) >= 144.0 - 1e-6,
                        "Zero hard overlap at step $step")
                }
                if (frame.units.all { it.moveTarget == null && it.moveArrival != null }) {
                    settledFrame = frame
                    break
                }
            }
            val done = assertNotNull(settledFrame, "All 3 queued tanks must clear the 1-tile throat and fan into the room: " +
                context.observe().units.map { "${it.unitId}@(${"%.1f".format(it.position.x)},${"%.1f".format(it.position.y)}) h=${"%.0f".format(it.heading)} spd=${"%.2f".format(it.speed)} tgt=${it.moveTarget} arr=${it.moveArrival != null}" })
            assertTrue(done.units.all { it.position.x > 218.0 },
                "All 3 tanks must pass through the throat instead of plugging col=10: ${done.units.map { it.unitId to it.position }}")
        } finally { context.close() }
    }

    @Test
    fun terminal_landing_settles_without_deadlock_inside_blind_single_tile_dead_end() {
        val base = TestContentFactory.openScenario()
        val tank = TestContentFactory.unitWith(base.unitDefinitions.getValue("tank"), radius = 6.0)
        // 1-tile wide blind dead-end corridor at row=10, cols 1..14, closed at col=15.
        val width = 20; val height = 22
        val blocked = BooleanArray(width * height) { idx ->
            val c = idx % width; val r = idx / width
            r != 10 || c == 0 || c >= 15
        }
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height),
            BlockingGrid(width, height, blocked))
        val spawns = (0 until 3).map { i ->
            UnitSpawn("d$i", "p1", "tank", Position(220.0 - i * 20.0, 210.0), 90.0)
        }
        val scene = base.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = spawns)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val click = Position(290.0, 210.0)
            val ids = spawns.map { it.unitId }
            val group = assertNotNull(context.requestGroupMove("p1", ids, click))
            ids.forEach { id ->
                assertIs<ActionResult.Accepted>(context.submitGroupMove(MoveAction("deadend-$id", "p1", id, click, context.world), group))
            }
            var settled: Observation? = null
            for (step in 0L..800L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                for (i in frame.units.indices) for (j in i + 1 until frame.units.size) {
                    assertTrue(ScreenPlane.distanceSquared(frame.units[i].position, frame.units[j].position) >= 144.0 - 1e-6,
                        "Zero hard overlap at step $step")
                }
                if (frame.units.all { it.moveTarget == null && it.moveArrival != null }) {
                    settled = frame
                    break
                }
            }
            assertNotNull(settled, "Blind 1-tile dead-end corridor must settle all 3 queued tanks without deadlock: " +
                context.observe().units.map { "${it.unitId}@(${"%.1f".format(it.position.x)},${"%.1f".format(it.position.y)}) tgt=${it.moveTarget}" })
        } finally { context.close() }
    }

    @Test
    fun pocket_topology_classifies_culdesac_interior_mouth_and_exterior_in_one_local_bfs() {
        val map = ScenarioCatalog.buildCulDeSacMap()
        val terrain = PlanningTerrain.fromNoFog(map)
        val click = Position(710.0, 260.0)
        val topo = PocketTopology.analyze(terrain, click, groupSize = 100)
        assertTrue(topo.hasPocket, "Cul-de-sac pocket must be detected topologically")
        assertTrue(topo.isInteriorAt(Position(710.0, 260.0)), "Deep click must be tagged interior")
        assertTrue(topo.isInteriorAt(Position(620.0, 260.0)), "Mid-pocket cell must be tagged interior")
        assertTrue(!topo.isInteriorAt(Position(500.0, 260.0)), "Exterior approach must not be tagged interior")
        assertTrue(!topo.isInteriorAt(Position(540.0, 340.0)), "Lower outer flank must not be tagged interior")
        assertTrue(topo.topologicalDepthAt(Position(710.0, 260.0)) > topo.topologicalDepthAt(Position(600.0, 260.0)),
            "Interior topological depth must increase toward the back of the pocket")
        assertTrue(topo.isInMouthBand(Position(560.0, 260.0)), "Mouth entrance x=560 must lie in the mouth band")
        assertTrue(topo.estimatedInteriorCapacity(6.0) >= 35, "Pocket capacity estimate must reflect real hull area")
    }

    @Test
    fun anonymous_slot_swap_exchanges_shallow_and_deep_claims_in_o1_without_detour_search() {
        val base = TestContentFactory.openScenario()
        val tank = TestContentFactory.unitWith(base.unitDefinitions.getValue("tank"), radius = 6.0)
        val map = ScenarioCatalog.buildCulDeSacMap()
        val scene = base.copy(
            map = map,
            unitDefinitions = mapOf("tank" to tank),
            units = listOf(
                UnitSpawn("front", "p1", "tank", Position(620.0, 260.0), 90.0),
                UnitSpawn("rear", "p1", "tank", Position(560.0, 260.0), 90.0),
            ),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val click = Position(710.0, 260.0)
            val group = assertNotNull(ctx.requestGroupMove("p1", listOf("front", "rear"), click))
            val stepsField = SimulationContext::class.java.getDeclaredField("steps").apply { isAccessible = true }
            val state = (stepsField.get(ctx) as CandidateSteps).current
            val hostField = SimulationContext::class.java.getDeclaredField("movementHost").apply { isAccessible = true }
            val host = hostField.get(ctx) as UnitMovementHost
            val front = state.units.getValue("front")
            val rear = state.units.getValue("rear")
            val shallowSite = Position(630.0, 260.0)
            val deepSite = Position(710.0, 260.0)
            front.updateNavigation {
                it.withLandingMember(LandingMember(group, claimed = shallowSite, claimedHeading = 90.0))
                    .bindCommandTarget(shallowSite, contract = MotionContract.sharedCluster(shallowSite))
                    .withPathPoints(listOf(shallowSite))
            }
            rear.updateNavigation {
                it.withLandingMember(LandingMember(group, claimed = deepSite, claimedHeading = 90.0))
                    .bindCommandTarget(deepSite, contract = MotionContract.sharedCluster(deepSite))
                    .withPathPoints(listOf(deepSite))
            }
            state.landingOccupancy.update(listOf(front, rear))
            val topo = PocketTopology.analyze(PlanningTerrain.fromNoFog(map), click, 2)
            assertTrue(SharedLandingSites.tryAnonymousSlotSwap(host, front, rear, topo),
                "Front shallow unit blocking rear deeper-claimed peer must swap claims atomically in O(1)")
            assertEquals(deepSite, front.landingMember?.claimed)
            assertEquals(deepSite, front.moveTarget)
            assertEquals(shallowSite, rear.landingMember?.claimed)
            assertEquals(shallowSite, rear.moveTarget)
            assertTrue("front" in state.landingOccupancy.at(deepSite))
            assertTrue("rear" in state.landingOccupancy.at(shallowSite))
        } finally {
            ctx.close()
        }
    }

    @Test
    fun two_tile_bottleneck_and_diagonal_chokepoint_prevent_wide_units_from_corking_the_gate() {
        val base = TestContentFactory.openScenario()
        val heavy = TestContentFactory.unitWith(base.unitDefinitions.getValue("tank"), radius = 14.0)
        // 2-tile vertical gate at col=10, rows 10..11 (40px wide < 4 * 14 = 56px -> two radius-14 hulls cannot pass abreast).
        val width = 32; val height = 22
        val blocked = BooleanArray(width * height) { idx ->
            val c = idx % width; val r = idx / width
            c == 10 && r !in 10..11
        }
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height),
            BlockingGrid(width, height, blocked))
        val spawns = listOf(
            UnitSpawn("h0", "p1", "heavy", Position(205.0, 220.0), 90.0),
            UnitSpawn("h1", "p1", "heavy", Position(155.0, 220.0), 90.0),
        )
        val scene = base.copy(map = map, unitDefinitions = mapOf("heavy" to heavy), units = spawns)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val click = Position(270.0, 220.0)
            val ids = spawns.map { it.unitId }
            val group = assertNotNull(context.requestGroupMove("p1", ids, click))
            ids.forEach { id ->
                assertIs<ActionResult.Accepted>(context.submitGroupMove(MoveAction("wide-$id", "p1", id, click, context.world), group))
            }
            var done: Observation? = null
            for (step in 0L..1200L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                if (frame.units.all { it.moveTarget == null && it.moveArrival != null }) {
                    done = frame
                    break
                }
            }
            val settled = assertNotNull(done, "Both radius-14 units must clear the 2-tile bottleneck and settle cleanly")
            assertTrue(settled.units.all { it.position.x > 220.0 },
                "Both heavy units must pass past the 2-tile throat at x=200..220: ${settled.units.map { it.unitId to it.position }}")
        } finally { context.close() }
    }

    @Test
    fun fourteen_reachable_members_expand_beyond_three_tiles_and_leave_buffer_space_deterministically() {
        data class Receipt(val step: Long, val trace: List<Int>, val positions: List<Position>)
        fun replay(inject: Boolean): Receipt {
            val opening = TestContentFactory.openScenario()
            val tank = TestContentFactory.unitWith(opening.unitDefinitions.getValue("tank"), radius = 19.0)
            val map = MapDefinition(64, 48, 20, 20, emptyList(), IntArray(64 * 48),
                BlockingGrid(64, 48, BooleanArray(64 * 48)))
            val scene = opening.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = (0 until 14).map {
                UnitSpawn("u$it", "p1", "tank", Position(80.0 + it * 80.0, 100.0), 180.0)
            })
            var injected = false
            val faultHook: (MutableUnit) -> Unit = { body ->
                if (inject && !injected && (body.landingMember?.flankSign ?: 0) != 0) {
                    injected = true
                    error("injected after shared claim")
                }
            }
            val initialized = SimulationContext(steeringFaultHook = faultHook).initialize(scene)
            val context = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
            try {
                context.enablePlanningWorkDiagnostics(context.world)
                val click = Position(640.0, 600.0)
                val ids = scene.units.map { it.unitId }
                val group = assertNotNull(context.requestGroupMove("p1", ids, click))
                ids.forEach { id -> assertIs<ActionResult.Accepted>(context.submitGroupMove(
                    MoveAction("select-$id", "p1", id, click, context.world), group)) }
                assertTrue(context.observe().units.all { it.moveTarget == null })
                var frame: Observation? = null
                val arrived = mutableSetOf<String>()
                val trace = mutableListOf<Int>()
                context.enableMotionDiagnostics(context.world)
                for (step in 0L..6000L) {
                    val tick = Step(step, 1.0, "sim-tick")
                    val next = try { context.advance(tick) } catch (failure: IllegalStateException) {
                        if (failure.message != "injected after shared claim") throw failure
                        assertTrue(context.drainMotionDiagnostics(context.world).isEmpty())
                        context.advance(tick)
                    }
                    val motion = context.drainMotionDiagnostics(context.world)
                    trace += listOf(next.events, motion.map { listOf(it.unitId, it.finalPosition, it.finalHeading,
                        it.steeringAim, it.commandTargetBefore, it.landingClaim, it.landingIngress, it.landingParked) }).hashCode()
                    assertTrue(next.events.none { it.kind == "group-destination-unavailable" }, "Reachable member lost its slot at $step")
                    next.events.filter { it.kind == "movement-arrived" }.mapTo(arrived) { requireNotNull(it.unitId) }
                    if (arrived.size == ids.size && next.units.all { it.moveTarget == null }) { frame = next; break }
                }
                val planned = assertNotNull(frame, "All fourteen members must occupy destinations: $arrived")
                val points = planned.units.map { it.position }
                assertTrue(points.any { ScreenPlane.distanceSquared(it, click) > 60.0 * 60.0 })
                for (a in points.indices) for (b in 0 until a) {
                    assertTrue(sqrt(ScreenPlane.distanceSquared(points[a], points[b])) >= 38.0 - 1e-7,
                        "Two radius-19 hulls must settle with zero hard overlap: ${points[a]} / ${points[b]}")
                }
                val work = context.drainPlanningWorkDiagnostics(context.world)
                assertTrue(work.size > 1)
                assertTrue(work.all { it.used <= it.limit })
                println("Expanded fourteen: settled=${planned.stepIndex} targets=$points maxWork=${work.maxOf { it.used }}")
                assertEquals(inject, injected)
                trace += work.hashCode()
                return Receipt(planned.stepIndex, trace, points)
            } finally { context.close() }
        }
        assertEquals(replay(false), replay(true))
    }
}
