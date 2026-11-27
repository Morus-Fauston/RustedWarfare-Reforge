package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NearestReachableMoveTest {
    @Test
    fun an_off_axis_immovable_circle_uses_the_analytic_nearest_contact() {
        val base = TestContentFactory.openScenario()
        val source = base.unitDefinitions.getValue("tank")
        val mover = UnitDefinition("tank", source.className, 6.0, source.displayRadius, source.totalFrames,
            source.imagePath, source.imageScale, source.movement, emptySet(), mass = 1000.0)
        val holder = UnitDefinition("holder", source.className, 6.0, source.displayRadius, source.totalFrames,
            source.imagePath, source.imageScale, source.movement.copy(moveSpeed = 0.0), emptySet(), mass = 1000.0)
        val scene = base.copy(map = MapDefinition(4, 4, 20, 20, emptyList(), IntArray(16),
            BlockingGrid(4, 4, BooleanArray(16))), unitDefinitions = mapOf("tank" to mover, "holder" to holder),
            units = listOf(UnitSpawn("mover", "p1", "tank", Position(10.0, 10.0), 90.0),
                UnitSpawn("holder", "p1", "holder", Position(40.0, 40.0), 0.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.enablePlanningWorkDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("off-axis", "p1", "mover",
                Position(42.0, 41.1), context.world)))
            var result: MoveArrival? = null
            for (tick in 0L..6000L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                val unit = frame.units.single { it.unitId == "mover" }
                assertTrue(kotlin.math.hypot(unit.position.x - 40.0, unit.position.y - 40.0) >= 12.0 - 1e-9)
                assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used <= it.limit })
                result = unit.moveArrival
                if (result != null) break
            }
            // Expanded-circle radius 12 minus |(2,1.1)|, then subtract moving radius 6.
            assertEquals(3.717457557897332, assertNotNull(result).distance, 0.000001)
            assertEquals(50.514590904922, result.stoppedAt.x, 0.00001)
            assertEquals(45.783024997707, result.stoppedAt.y, 0.00001)
        } finally { context.close() }
    }

    @Test
    fun a_group_rejects_only_the_fully_enclosed_member_and_finishes_the_mobile_member() {
        val base = TestContentFactory.openScenario()
        val tank = TestContentFactory.unitWith(base.unitDefinitions.getValue("tank"), radius = 10.0)
        val width = 40; val height = 20
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height),
            BlockingGrid(width, height, BooleanArray(width * height) {
                val col = it % width; val row = it / width
                col in 4..6 && row in 4..6 && !(col == 5 && row == 5)
            }))
        val scene = base.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = listOf(
            UnitSpawn("boxed", "p1", "tank", Position(110.0, 110.0), 90.0),
            UnitSpawn("free", "p1", "tank", Position(400.0, 210.0), 90.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            // Exact success at the boxed member's own center must not bypass rejection.
            val click = Position(110.0, 110.0)
            val group = assertNotNull(context.requestGroupMove("p1", listOf("boxed", "free"), click))
            for (id in listOf("boxed", "free")) assertIs<ActionResult.Accepted>(context.submitGroupMove(
                MoveAction("go-$id", "p1", id, click, context.world), group))
            var rejection: ActionResult.Rejected? = null
            var arrived = false
            for (tick in 0L..10000L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                assertTrue(frame.events.none { it.kind in setOf("movement-cancelled", "path-unreachable", "group-destination-unavailable") })
                rejection = frame.actionResults.filterIsInstance<ActionResult.Rejected>().firstOrNull { it.actionId == "go-boxed" } ?: rejection
                arrived = arrived || frame.units.single { it.unitId == "free" }.moveArrival != null
                if (rejection != null && arrived) break
            }
            assertEquals("immobile-unit", assertNotNull(rejection).error.code)
            assertTrue(arrived)
            assertEquals(Position(110.0, 110.0), context.observePlayer(context.world, "p1").units.single { it.unitId == "boxed" }.position)
        } finally { context.close() }
    }

    @Test
    fun a_ship_stops_normally_when_the_channel_is_too_narrow_or_the_bow_cannot_turn_out() =
        WaterTestScene.withMap { shore ->
            for (deadEnd in listOf(false, true)) {
                val width = if (deadEnd) 24 else 40
                val height = if (deadEnd) 12 else 20
                val water = shore.groundGids.last()
                val land = shore.groundGids.first()
                val map = MapDefinition(width, height, 20, 20, shore.tileSets,
                    IntArray(width * height) {
                        val col = it % width; val row = it / width
                        val wet = if (deadEnd) row == 5 && col in 1..20 else col >= 20 || row == 10
                        if (wet) water else land
                    }, BlockingGrid(width, height, BooleanArray(width * height)))
                val shape = if (deadEnd) MovementCollisionShape.Rectangle(102.0, 20.0)
                    else MovementCollisionShape.Rectangle(60.0, 24.0)
                val source = WaterTestScene.unit(MovementType.WATER)
                val ship = UnitDefinition(source.id, source.className, 6.0, source.displayRadius, source.totalFrames,
                    source.imagePath, source.imageScale, source.movement, emptySet(), movementCollision = shape, mass = 3000.0)
                val start = if (deadEnd) Position(240.0, 110.0) else Position(600.0, 210.0)
                val click = if (deadEnd) Position(110.0, 110.0) else Position(100.0, 210.0)
                val scene = WaterTestScene.scenario(map, MovementType.WATER, start).copy(
                    unitDefinitions = mapOf(ship.id to ship),
                    units = listOf(UnitSpawn("w1", "p1", ship.id, start, if (deadEnd) 90.0 else 270.0)))
                val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
                try {
                    context.enablePlanningWorkDiagnostics(context.world)
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("channel", "p1", "w1", click, context.world)))
                    var stopped: MoveArrival? = null
                    for (tick in 0L..6000L) {
                        val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                        assertTrue(frame.events.none { it.kind == "movement-cancelled" || it.kind == "path-unreachable" })
                        val unit = context.observePlayer(context.world, "p1").units.single()
                        assertTrue(UnitContact.poseFits(shape, unit.position, unit.heading,
                            HullTerrainGrid.authoritative(map, MovementType.WATER)))
                        assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used <= it.limit })
                        stopped = unit.moveArrival
                        if (stopped != null) break
                    }
                    val result = assertNotNull(stopped, "Channel order must finish: deadEnd=$deadEnd")
                    if (deadEnd) {
                        assertEquals(start, result.stoppedAt)
                        assertEquals(79.0, result.distance, 0.00001)
                    } else {
                        assertTrue(result.stoppedAt.x < 500.0)
                        assertTrue(result.distance in 250.0..320.0)
                    }
                } finally { context.close() }
            }
        }

    @Test
    fun a_fully_trapped_unit_is_rejected_but_a_touching_target_with_room_to_move_is_accepted() {
        val base = TestContentFactory.openScenario()
        val tank = TestContentFactory.unitWith(base.unitDefinitions.getValue("tank"), radius = 10.0)
        for (clickX in listOf(10.0, 19.0)) for (free in listOf(true, false)) {
            val rows = if (free) 7 else 1
            val start = Position(10.0, if (free) 100.0 else 10.0)
            val map = MapDefinition(1, rows, 20, 20, emptyList(), IntArray(rows),
                BlockingGrid(1, rows, BooleanArray(rows)))
            val scene = base.copy(map = map, unitDefinitions = mapOf("tank" to tank),
                units = listOf(UnitSpawn("u1", "p1", "tank", start, 0.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val receipt = context.submit(MoveAction("boxed", "p1", "u1", Position(clickX, start.y), context.world))
                var rejected = receipt as? ActionResult.Rejected
                var arrived: MoveArrival? = null
                for (tick in 0L..1000L) {
                    if (rejected != null) break
                    val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                    assertTrue(frame.events.none { it.kind == "movement-cancelled" || it.kind == "path-unreachable" })
                    rejected = frame.actionResults.filterIsInstance<ActionResult.Rejected>().firstOrNull()
                    arrived = context.observePlayer(context.world, "p1").units.single().moveArrival
                    if (arrived != null) break
                }
                if (free) {
                    assertEquals(null, rejected)
                    assertEquals(0.0, assertNotNull(arrived).distance)
                } else {
                    assertEquals("immobile-unit", assertNotNull(rejected, "A completely enclosed hull must be rejected").error.code)
                    assertTrue(context.observePlayer(context.world, "p1").units.single().tasks.isEmpty())
                }
            } finally { context.close() }
        }
    }

    @Test
    fun outside_targets_and_units_without_propulsion_are_rejected_with_a_reason() {
        val base = TestContentFactory.openScenario()
        for (immobile in listOf(false, true)) {
            val source = base.unitDefinitions.getValue("tank")
            val tank = TestContentFactory.unitWith(source,
                movement = source.movement.copy(moveSpeed = if (immobile) 0.0 else source.movement.moveSpeed))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
                unitDefinitions = mapOf("tank" to tank)))).context
            try {
                val click = if (immobile) Position(200.0, 100.0) else Position(-1.0, 100.0)
                val result = assertIs<ActionResult.Rejected>(context.submit(MoveAction("invalid", "p1", "u1", click, context.world)))
                assertEquals(if (immobile) "immobile-unit" else "invalid-target", result.error.code)
                assertTrue(context.observePlayer(context.world, "p1").units.single().tasks.isEmpty())
            } finally { context.close() }
        }
    }

    @Test
    fun members_on_both_sides_of_a_barrier_finish_the_shared_order_and_keep_the_original_click() {
        val source = TestContentFactory.openScenario()
        val tank = TestContentFactory.unitWith(source.unitDefinitions.getValue("tank"), radius = 6.0)
        val map = MapDefinition(32, 24, 20, 20, emptyList(), IntArray(32 * 24),
            BlockingGrid(32, 24, BooleanArray(32 * 24) { it % 32 == 16 }))
        val scene = source.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = listOf(
            UnitSpawn("left", "p1", "tank", Position(100.0, 200.0), 90.0),
            UnitSpawn("right", "p1", "tank", Position(500.0, 100.0), 180.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val click = Position(550.0, 200.0)
            val group = assertNotNull(context.requestGroupMove("p1", listOf("left", "right"), click))
            for (id in listOf("left", "right")) assertIs<ActionResult.Accepted>(context.submitGroupMove(
                MoveAction("go-$id", "p1", id, click, context.world), group))
            var done = false
            for (tick in 0L..10000L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                assertTrue(frame.events.none { it.kind == "path-unreachable" || it.kind == "group-destination-unavailable" })
                if (frame.units.all { it.moveArrival != null }) {
                    val results = frame.units.associate { it.unitId to assertNotNull(it.moveArrival) }
                    assertTrue(results.values.all { it.originalTarget == click && it.group })
                    frame.units.forEach { assertEquals(it.position, results.getValue(it.unitId).stoppedAt) }
                    assertEquals(230.0, results.getValue("left").distance, 0.00001)
                    assertEquals(0.0, results.getValue("right").distance, 1.1)
                    assertTrue(frame.units.all { it.tasks.isEmpty() && it.moveTarget == null })
                    val stopped = frame.units.map { it.position to it.heading }
                    repeat(600) { offset ->
                        val quiet = context.advance(Step(frame.stepIndex + offset + 1L, 1.0, "sim-tick"))
                        assertEquals(stopped, quiet.units.map { it.position to it.heading })
                    }
                    done = true
                    break
                }
            }
            assertTrue(done, "Both sides must finish with player-authorized results: ${context.observe().units}")
        } finally { context.close() }
    }

    @Test
    fun a_single_order_pushes_a_light_idle_holder_but_stops_at_an_immovable_holder() {
        val base = TestContentFactory.openScenario()
        val source = base.unitDefinitions.getValue("tank")
        val mover = UnitDefinition("tank", source.className, 6.0, source.displayRadius, source.totalFrames,
            source.imagePath, source.imageScale, source.movement, emptySet(), mass = 1000.0)
        for (mode in listOf("pushable", "anchored", "too-heavy")) {
            val anchored = mode != "pushable"
            val holder = UnitDefinition("holder", mover.className, mover.radius, mover.displayRadius,
                mover.totalFrames, mover.imagePath, mover.imageScale,
                mover.movement.copy(moveSpeed = if (mode == "anchored") 0.0 else mover.movement.moveSpeed), emptySet(),
                mass = if (mode == "too-heavy") 10000.0 else mover.mass)
            val target = Position(280.0, 200.0)
            val scene = base.copy(unitDefinitions = mapOf("tank" to mover, holder.id to holder), units = listOf(
                UnitSpawn("mover", "p1", "tank", Position(100.0, 200.0), 90.0),
                UnitSpawn("holder", "p1", holder.id, target, 90.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("occupied", "p1", "mover", target, context.world)))
                var done = false
                for (tick in 0L..4000L) {
                    val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                    assertTrue(frame.events.none { it.kind == "path-unreachable" || it.kind == "movement-cancelled" })
                    if (frame.events.any { it.kind == "movement-arrived" && it.unitId == "mover" }) {
                        val unit = frame.units.single { it.unitId == "mover" }
                        if (anchored) {
                            assertEquals(12.0, kotlin.math.hypot(unit.position.x - target.x,
                                unit.position.y - target.y), 0.00001)
                            assertEquals(6.0, assertNotNull(unit.moveArrival).distance, 0.00001)
                            assertEquals(target, frame.units.single { it.unitId == "holder" }.position)
                        } else {
                            assertTrue(kotlin.math.hypot(unit.position.x - target.x, unit.position.y - target.y) <= 6.0,
                                "The moving hull must occupy the clicked point: ${unit.position}")
                            assertTrue(frame.units.single { it.unitId == "holder" }.position.x > target.x)
                        }
                        done = true
                        break
                    }
                }
                assertTrue(done, "Occupied order must finish: anchored=$anchored; ${context.observe().units}")
            } finally { context.close() }
        }
    }

    @Test
    fun a_ship_bow_touching_the_requested_shore_has_zero_deviation_and_foreign_players_get_no_result() =
        WaterTestScene.withMap { map ->
            val source = WaterTestScene.unit(MovementType.WATER)
            val ship = UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
                source.totalFrames, source.imagePath, source.imageScale, source.movement, emptySet(),
                movementCollision = MovementCollisionShape.Rectangle(102.0, 20.0))
            for ((click, expected) in listOf(Position(400.0, 200.0) to 0.0, Position(350.0, 200.0) to 50.0)) {
                val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(600.0, 200.0)).copy(
                    unitDefinitions = mapOf(ship.id to ship),
                    units = listOf(UnitSpawn("w1", "p1", ship.id, Position(600.0, 200.0), 270.0)))
                val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
                try {
                    context.enablePlanningWorkDiagnostics(context.world)
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("shore", "p1", "w1", click, context.world)))
                    var arrived = false
                    for (tick in 0L..6000L) {
                        val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                        assertTrue(frame.events.none { it.kind == "path-unreachable" || it.kind == "movement-cancelled" })
                        assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used <= it.limit })
                        if (frame.events.any { it.kind == "movement-nearest-reached" }) {
                            val own = context.observePlayer(context.world, "p1")
                            val result = assertNotNull(own.units.single().moveArrival)
                            assertEquals(click, result.originalTarget)
                            assertEquals(expected, result.distance, 0.000001)
                            assertEquals(result.stoppedAt, frame.units.single().position)
                            assertEquals(451.0, result.stoppedAt.x, 0.000001)
                            val foreign = context.observePlayer(context.world, "p2")
                            assertTrue(foreign.units.all { it.moveArrival == null })
                            assertTrue(foreign.events.none { it.kind == "movement-nearest-reached" })
                            arrived = true
                            break
                        }
                    }
                    assertTrue(arrived, "The full ship must sail to its continuous nearest shoreline stance for $click")
                } finally { context.close() }
            }
        }

    @Test
    fun an_order_across_disconnected_terrain_moves_to_the_nearest_reachable_side_and_reports_it() {
        val source = TestContentFactory.openScenario()
        val tank = TestContentFactory.unitWith(source.unitDefinitions.getValue("tank"), radius = 6.0)
        val map = MapDefinition(32, 24, 20, 20, emptyList(), IntArray(32 * 24),
            BlockingGrid(32, 24, BooleanArray(32 * 24) { it % 32 == 16 }))
        val scene = source.copy(map = map, unitDefinitions = mapOf("tank" to tank),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(100.0, 200.0), 90.0)))
        for (targetY in listOf(200.0, 191.0)) {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.enablePlanningWorkDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("across", "p1", "u1",
                Position(550.0, targetY), context.world)))
            var reported = false
            for (step in 0L..6000L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                assertTrue(frame.events.none { it.kind == "path-unreachable" || it.kind == "movement-cancelled" },
                    "Unexpected cancellation at $step: ${frame.events}")
                assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used <= it.limit })
                if (frame.events.any { it.kind == "movement-nearest-reached" }) {
                    val unit = frame.units.single()
                    assertTrue(unit.position.x in 313.99..314.01)
                    assertEquals(targetY, unit.position.y, 0.00001)
                    assertEquals(230.0, assertNotNull(unit.moveArrival).distance, 0.00001)
                    assertTrue(unit.tasks.isEmpty() && unit.moveTarget == null)
                    reported = true
                    break
                }
            }
            assertTrue(reported, "Disconnected orders must approach and report instead of cancelling")
        } finally { context.close() }
        }
    }
}
