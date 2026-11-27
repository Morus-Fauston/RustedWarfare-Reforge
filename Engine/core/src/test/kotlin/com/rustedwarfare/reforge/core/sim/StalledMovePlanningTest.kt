package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.core.path.PathSearchOutcome
import com.rustedwarfare.reforge.core.path.PlanningWorkSample
import com.rustedwarfare.reforge.core.path.PathDiagnosticsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class StalledMovePlanningTest {
    @Test
    fun work_diagnostics_require_their_own_trusted_opt_in_and_preserve_host_and_player_results() = WaterTestScene.withMap { map ->
        val scene = jam(map)
        val measured = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val plain = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val world = measured.world
        try {
            plain.enablePathDiagnostics(plain.world)
            assertEquals("not-enabled", assertFailsWith<PathDiagnosticsException> { plain.drainPlanningWorkDiagnostics(plain.world) }.code)
            assertEquals("stale-world", assertFailsWith<PathDiagnosticsException> { measured.enablePlanningWorkDiagnostics(plain.world) }.code)
            measured.enablePlanningWorkDiagnostics(world)
            assertEquals("already-enabled", assertFailsWith<PathDiagnosticsException> { measured.enablePlanningWorkDiagnostics(world) }.code)
            for (context in listOf(measured, plain)) for (index in 0..3) assertIs<ActionResult.Accepted>(context.submit(
                MoveAction("go-$index", "p1", "m$index", Position(340.0, 70.0 + index * 70.0), context.world)))
            for (tick in 0L..100L) {
                val a = measured.advance(Step(tick, 1.0, "sim-tick"))
                val b = plain.advance(Step(tick, 1.0, "sim-tick"))
                assertEquals(a.units, b.units)
                assertEquals(a.events, b.events)
                assertEquals(measured.observePlayer(world, "p1").units, plain.observePlayer(plain.world, "p1").units)
            }
            assertEquals(101, measured.drainPlanningWorkDiagnostics(world).size)
            assertTrue(measured.drainPlanningWorkDiagnostics(world).isEmpty())
            assertEquals("stale-world", assertFailsWith<PathDiagnosticsException> { measured.drainPlanningWorkDiagnostics(plain.world) }.code)
        } finally { measured.close(); plain.close() }
        assertEquals("closed", assertFailsWith<PathDiagnosticsException> { measured.drainPlanningWorkDiagnostics(world) }.code)
    }

    @Test
    fun simultaneous_contact_repairs_enter_the_step_queue_instead_of_searching_synchronously() =
        WaterTestScene.withMap { map ->
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(jam(map))).context
            try {
                context.enablePathDiagnostics(context.world)
                context.enablePlanningWorkDiagnostics(context.world)
                for (index in 0..3) assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("go-$index", "p1", "m$index", Position(340.0, 70.0 + index * 70.0), context.world)))
                for (tick in 0L..60L) context.advance(Step(tick, 1.0, "sim-tick"))
                val repairs = context.drainPathDiagnostics(context.world).filter { it.reason == PathSearchReason.ROUTE_REPAIR }
                println("contact repairs in trigger step: $repairs")
                assertTrue(repairs.none { it.executionStep == 60L }, "Recovery must enter the shared queue for a later step")
                assertTrue(context.observe().units.filter { it.unitId.startsWith("m") }.all {
                    it.position == Position(150.0, 70.0 + it.unitId.drop(1).toInt() * 70.0) && it.tasks.size == 1
                })
                for (tick in 61L..1000L) context.advance(Step(tick, 1.0, "sim-tick"))
                val planned = context.drainPathDiagnostics(context.world).filter { it.reason == PathSearchReason.ROUTE_REPAIR }
                assertEquals((0..3).map { "m$it" }.toSet(), planned.mapTo(mutableSetOf()) { it.unitId })
                assertTrue(context.observe().units.filter { it.unitId.startsWith("m") }.all { it.position.x > 200.0 })
                val work = context.drainPlanningWorkDiagnostics(context.world)
                assertTrue(work.all { it.limit == 512 && it.used in 0..512 && it.slices.sumOf { s -> s.workUnits } == it.used })
                assertTrue(work.any { it.slices.any { s -> s.reason == PathSearchReason.ROUTE_REPAIR } })
                println("contact queue work=${work.filter { it.used > 0 }}; published=$planned")
            } finally { context.close() }
        }

    @Test
    fun a_repair_invalidated_by_continuing_pushes_yields_to_the_waiting_tail() = WaterTestScene.withMap { source ->
        val width = 160
        val height = 100
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height),
            BlockingGrid(width, height, BooleanArray(width * height)))
        val base = jam(source)
        val mover = base.unitDefinitions.getValue("mover")
        val anchor = base.unitDefinitions.getValue("anchor")
        val pusher = unit("pusher", 1.0, mass = 30000.0)
        val scene = base.copy(map = map, unitDefinitions = base.unitDefinitions + (pusher.id to pusher), units = listOf(
            UnitSpawn("a0", "p1", anchor.id, Position(162.0, 70.0), 90.0),
            UnitSpawn("a1", "p1", anchor.id, Position(162.0, 500.0), 90.0),
            UnitSpawn("m0", "p1", mover.id, Position(150.0, 70.0), 90.0),
            UnitSpawn("m1", "p1", mover.id, Position(150.0, 500.0), 90.0),
            UnitSpawn("z-pusher", "p1", pusher.id, Position(150.0, 144.0), 0.0)))
        val context = assertIs<InitResult.Accepted>(
            SimulationContext(movePlanningBudgetPerStep = 8).initialize(scene),
        ).context
        try {
            context.enablePathDiagnostics(context.world)
                context.enablePlanningWorkDiagnostics(context.world)
            for ((id, target) in listOf("m0" to Position(340.0, 70.0), "m1" to Position(340.0, 500.0),
                "z-pusher" to Position(150.0, 20.0))) assertIs<ActionResult.Accepted>(context.submit(
                MoveAction("go-$id", "p1", id, target, context.world)))
            for (tick in 0L..150L) context.advance(Step(tick, 1.0, "sim-tick"))
            val work = context.drainPlanningWorkDiagnostics(context.world)
            val repairs = context.drainPathDiagnostics(context.world).filter { it.reason == PathSearchReason.ROUTE_REPAIR }
            println("invalidated queue work=${work.filter { it.used > 0 }}; published=$repairs")
            assertTrue(work.any { it.slices.any { s -> "m0" in s.unitIds && s.restarted } }, "Fixture must invalidate a real active search")
            assertTrue(repairs.any { it.unitId == "m1" }, "Independent tail must finish while the pushed head keeps invalidating")
            assertTrue(context.observe().units.single { it.unitId == "m1" }.position.x > 150.0)
        } finally { context.close() }
    }

    @Test
    fun contact_recovery_and_long_ship_orders_share_one_unchanged_budget_deterministically() = WaterTestScene.withMap { map ->
        val original = WaterTestScene.unit(MovementType.WATER)
        val ship = UnitDefinition("long-ship", original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, emptySet(),
            movementCollision = MovementCollisionShape.Rectangle(80.0, 20.0), mass = 3000.0)
        val base = jam(map)
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (ship.id to ship), units = base.units + listOf(
            UnitSpawn("s0", "p1", ship.id, Position(500.0, 130.0), 270.0),
            UnitSpawn("s1", "p1", ship.id, Position(500.0, 270.0), 270.0)))
        data class Pose(val id: String, val position: Position, val heading: Double, val target: Position?, val path: List<Position>)
        data class Replay(val work: List<PlanningWorkSample>, val frames: List<Pair<List<Pose>, List<Event>>>,
                          val firstTravel: Map<String, Long>)
        fun replay(): Replay {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                context.enablePathDiagnostics(context.world)
                context.enablePlanningWorkDiagnostics(context.world)
                for (index in 0..3) assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("go-$index", "p1", "m$index", Position(340.0, 70.0 + index * 70.0), context.world)))
                val frames = mutableListOf<Pair<List<Pose>, List<Event>>>()
                val firstTravel = mutableMapOf<String, Long>()
                for (tick in 0L..1200L) {
                    if (tick == 60L) {
                        val target = Position(700.0, 200.0)
                        val group = kotlin.test.assertNotNull(context.requestGroupMove("p1", listOf("s0", "s1"), target))
                        for (id in listOf("s0", "s1")) assertIs<ActionResult.Accepted>(context.submitGroupMove(
                            MoveAction("ship-$id", "p1", id, target, context.world), group))
                    }
                    val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                    frames.add(frame.units.map { Pose(it.unitId, it.position, it.heading, it.moveTarget, it.pathPoints) } to frame.events)
                    for (u in frame.units.filter { it.unitId.startsWith("m") && it.position.x > 150.0 }) firstTravel.putIfAbsent(u.unitId, tick)
                }
                val work = context.drainPlanningWorkDiagnostics(context.world)
                val repairs = context.drainPathDiagnostics(context.world).filter { it.reason == PathSearchReason.ROUTE_REPAIR }
                assertEquals((0..3).map { "m$it" }.toSet(), firstTravel.keys)
                assertTrue(work.all { it.limit == 512 && it.used in 0..512 && it.slices.sumOf { s -> s.workUnits } == it.used })
                assertTrue(work.any { it.executionStep >= 60 && it.slices.any { s -> s.reason == PathSearchReason.USER_MOVE && "s0" in s.unitIds } })
                assertEquals(firstTravel.keys, repairs.mapTo(mutableSetOf()) { it.unitId })
                println("mixed work=${work.filter { it.used > 0 }}; firstTravel=$firstTravel; repairs=${repairs.map { it.unitId to it.executionStep }}")
                return Replay(work, frames, firstTravel.toMap())
            } finally { context.close() }
        }
        assertEquals(replay(), replay())
    }

    @Test
    fun an_unchanged_impossible_contact_does_not_repeat_recovery_forever() =
        WaterTestScene.withCorridor(dryBarrier = false) { map ->
            val original = WaterTestScene.unit(MovementType.WATER)
            val anchor = UnitDefinition("anchor", original.className, original.radius, original.displayRadius,
                original.totalFrames, original.imagePath, original.imageScale, original.movement.copy(moveSpeed = 0.0), emptySet())
            val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(10.0, 100.0)).copy(
                unitDefinitions = mapOf(original.id to original, anchor.id to anchor), units = listOf(
                    UnitSpawn("mover", "p1", original.id, Position(10.0, 100.0), 0.0),
                    UnitSpawn("anchor", "p1", anchor.id, Position(10.0, 88.0), 0.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                context.enablePathDiagnostics(context.world)
                context.enablePlanningWorkDiagnostics(context.world)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("go", "p1", "mover", Position(10.0, 25.0), context.world)))
                var stoppedAt: Long? = null
                for (tick in 0L..1800L) {
                    val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                    assertTrue(frame.events.none { it.kind == "movement-cancelled" || it.kind == "path-unreachable" })
                    if (frame.events.any { it.kind == "movement-nearest-reached" }) stoppedAt = tick
                }
                val repairs = context.drainPathDiagnostics(context.world).filter { it.reason == PathSearchReason.ROUTE_REPAIR }
                assertTrue(repairs.size in 1..7, "Bound the old failure policy without adding endless retries: $repairs")
                assertTrue(repairs.last().outcome == PathSearchOutcome.FOUND)
                assertTrue(stoppedAt != null && stoppedAt >= 420L, "Static no-route must stop with a result after the failure threshold")
                val mover = context.observePlayer(context.world, "p1").units.single { it.unitId == "mover" }
                assertEquals(Position(10.0, 100.0), mover.position)
                assertEquals(69.0, kotlin.test.assertNotNull(mover.moveArrival).distance, 0.00001)
                assertTrue(mover.tasks.isEmpty() && mover.moveTarget == null)
                val work = context.drainPlanningWorkDiagnostics(context.world)
                assertTrue(work.all { it.used in 0..512 && it.slices.sumOf { s -> s.workUnits } == it.used })
                assertTrue(work.filter { it.executionStep > 600 }.all { it.used == 0 })
                println("impossible contact recovery attempts=${repairs.size}; steps=${repairs.map { it.executionStep }}")
            } finally { context.close() }
        }

    @Test
    fun stop_and_replacement_cancel_in_flight_contact_repairs_before_they_publish() = WaterTestScene.withMap { source ->
        val width = 160
        val height = 100
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height),
            BlockingGrid(width, height, BooleanArray(width * height)))
        val context = assertIs<InitResult.Accepted>(
            SimulationContext(movePlanningBudgetPerStep = 24).initialize(jam(source).copy(map = map)),
        ).context
        try {
            context.enablePathDiagnostics(context.world)
                context.enablePlanningWorkDiagnostics(context.world)
            for (index in 0..3) assertIs<ActionResult.Accepted>(context.submit(
                MoveAction("go-$index", "p1", "m$index", Position(340.0, 70.0 + index * 70.0), context.world)))
            for (tick in 0L..62L) context.advance(Step(tick, 1.0, "sim-tick"))
            assertTrue(context.drainPlanningWorkDiagnostics(context.world).any { sample ->
                sample.slices.any { it.reason == PathSearchReason.ROUTE_REPAIR && !it.completed }
            })
            assertIs<ActionResult.Accepted>(context.submit(StopAction("halt-m0", "p1", "m0", context.world)))
            val replacement = Position(100.0, 140.0)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("replace-m1", "p1", "m1", replacement, context.world)))
            for (tick in 63L..400L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                assertEquals(Position(150.0, 70.0), frame.units.single { it.unitId == "m0" }.position)
                assertTrue(frame.events.none { it.kind == "movement-started" && it.unitId == "m0" })
            }
            val planned = context.drainPathDiagnostics(context.world)
            assertTrue(planned.none { it.reason == PathSearchReason.ROUTE_REPAIR && it.unitId in setOf("m0", "m1") })
            assertEquals(replacement, planned.single { it.actionId == "replace-m1" }.searchTarget)
            assertTrue(context.observe().units.single { it.unitId == "m1" }.position.x < 150.0)
        } finally { context.close() }
    }

    private fun jam(map: MapDefinition): Scenario {
        val mover = unit("mover", .7)
        val anchor = unit("anchor", 0.0)
        return WaterTestScene.scenario(map, MovementType.LAND, Position(150.0, 70.0)).copy(
            unitDefinitions = listOf(mover, anchor).associateBy { it.id },
            units = (0..3).flatMap { index ->
                val y = 70.0 + index * 70.0
                listOf(UnitSpawn("m$index", "p1", mover.id, Position(150.0, y), 90.0),
                    UnitSpawn("a$index", "p1", anchor.id, Position(162.0, y), 90.0))
            })
    }

    private fun unit(id: String, speed: Double, mass: Double = 1000.0): UnitDefinition {
        val original = WaterTestScene.unit(MovementType.LAND)
        return UnitDefinition(id, original.className, original.radius, original.displayRadius, original.totalFrames,
            original.imagePath, original.imageScale, original.movement.copy(moveSpeed = speed,
                moveAccelerationSpeed = 1.0, moveDecelerationSpeed = 1.0, maxTurnSpeed = 90.0, turnAcceleration = 0.0),
            emptySet(), mass = mass)
    }
}
