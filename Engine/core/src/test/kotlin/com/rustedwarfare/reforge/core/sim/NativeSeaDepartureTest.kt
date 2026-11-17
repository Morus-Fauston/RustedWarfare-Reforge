package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeSeaDepartureTest {
    @Test
    fun a_departed_rectangle_cannot_be_pushed_back_into_its_factory_from_the_side() = WaterTestScene.withMap { map ->
        val scene = nativeScene(map, rectangle = true,
            UnitSpawn("pusher", "p1", "port-marker", Position(447.0, 150.0), 150.0), markerMass = 40000.0)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val frames = Frames(context)
            val ship = produce(context, "b")
            frames.until(3100) { it.units.any { unit -> unit.unitId == ship && unit.moveTarget == null } }
            for ((id, target) in listOf("south-west" to Position(465.0, 300.0), "along-side" to Position(465.0, 170.0))) {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction(id, "p1", ship, target, context.world)))
                frames.until(5000) { Event("movement-arrived", ship) in it.events }
            }
            assertIs<ActionResult.Accepted>(context.submit(StopAction("park-beside", "p1", ship, context.world)))
            repeat(100) { frames.next() }
            val parked = context.observe().unit(ship)
            fun clearFactory(unit: UnitObservation): Boolean {
                val radians = Math.toRadians(unit.heading)
                val x = kotlin.math.abs(kotlin.math.sin(radians)) * 51.0 + kotlin.math.abs(kotlin.math.cos(radians)) * 10.0
                val y = kotlin.math.abs(kotlin.math.cos(radians)) * 51.0 + kotlin.math.abs(kotlin.math.sin(radians)) * 10.0
                if (unit.position.x + x <= 480.0 + 1e-8 || unit.position.x - x >= 540.0 - 1e-8 ||
                    unit.position.y + y <= 120.0 + 1e-8 || unit.position.y - y >= 200.0 - 1e-8) return true
                // A rotated hull's bounding box may touch a factory corner while the hull stays clear.
                val forward = kotlin.math.sin(radians) to -kotlin.math.cos(radians)
                val right = -forward.second to forward.first
                return listOf(forward to 51.0, right to 10.0).any { (axis, half) ->
                    val offset = kotlin.math.abs((unit.position.x - 510.0) * axis.first +
                        (unit.position.y - 160.0) * axis.second)
                    offset >= half + kotlin.math.abs(axis.first) * 30.0 + kotlin.math.abs(axis.second) * 40.0 - 1e-8
                }
            }
            assertTrue(clearFactory(parked), "fixture has not cleared factory: $parked")
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("diagonal-pressure", "p1", "pusher",
                Position(490.0, 250.0), context.world)))
            repeat(600) {
                val frame = frames.next()
                assertTrue(clearFactory(frame.unit(ship)), "departed ship re-entered producer at ${frame.stepIndex}: ${frame.unit(ship)}")
            }
            println("Departed rectangle stayed outside producer: parked=${parked.position}/${parked.heading}, end=${context.observe().unit(ship).position}")
        } finally { context.close() }
    }

    @Test
    fun the_next_birth_pushes_a_stopped_ship_whose_stern_still_overlaps_its_factory() = WaterTestScene.withMap { map ->
      for (rectangle in listOf(false, true)) {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(nativeScene(map, rectangle))).context
        try {
            val frames = Frames(context)
            val first = produce(context, "b")
            frames.until(2800) { it.units.any { ship -> ship.unitId == first && ship.position.y >= 205.0 } }
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-in-outlet", "p1", first, context.world)))
            repeat(100) { frames.next() }
            val stopped = context.observe().unit(first)
            assertTrue(stopped.position.y - 40.0 < 200.0, "stern already clear: $stopped")
            assertEquals(0.0, stopped.speed)
            val second = produce(context, "a")
            frames.until(2600) { Event("unit-produced", second) in it.events }
            var previous = context.observe().unit(first).position
            var pushed = 0
            repeat(1400) {
                val frame = frames.next()
                val ship = frame.unit(first)
                val travel = kotlin.math.sqrt(ScreenPlane.distanceSquared(previous, ship.position))
                assertTrue(travel <= .7 + 1e-8, "stopped ship jumped $travel")
                if (travel > 1e-8) pushed++
                assertEquals(stopped.heading, ship.heading)
                assertTrue(ship.tasks.isEmpty())
                previous = ship.position
            }
            assertTrue(pushed > 1, "stopped ship was never gradually pushed: $previous")
            assertTrue(context.observe().unit(second).position.y > 260.0, "following ship did not leave")
            assertEquals(18000L, context.observe().economy!!.balances.getValue("p1"))
            assertEquals(setOf(first, second), context.observe().production!!.units.map { it.unitId }.toSet())
            println("Stopped stern cleared rectangle=$rectangle: start=${stopped.position}, end=$previous, pushedSteps=$pushed")
        } finally { context.close() }
      }
    }

    @Test
    fun a_paid_departure_pushes_idle_own_allied_and_enemy_units_gradually() = WaterTestScene.withMap { map ->
        val occupied = Position(510.0, 230.0)
        val nearby = Position(554.0, 224.0)
        for ((owner, team) in listOf("p1" to "enemy", "p2" to "own", "p2" to "enemy")) {
            val scene = nativeScene(map, rectangle = true,
                UnitSpawn("direct", owner, "port-marker", occupied, 90.0),
                UnitSpawn("nearby", "p1", "port-marker", nearby, 90.0)).copy(
                players = listOf(Player("p1", PlayerControl.LOCAL, "own"), Player("p2", PlayerControl.LOCAL, team)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val ship = produce(context, "a")
                val ticks = Frames(context)
                ticks.until(2600) { Event("unit-produced", ship) in it.events }
                var previous = occupied
                var displacedSteps = 0
                repeat(700) {
                    val frame = ticks.next()
                    val blocker = frame.unit("direct")
                    val travel = kotlin.math.sqrt(ScreenPlane.distanceSquared(previous, blocker.position))
                    assertTrue(travel <= .7 + 1e-8, "$owner/$team jumped $travel at ${frame.stepIndex}")
                    if (travel > 1e-8) displacedSteps++
                    assertTrue(blocker.tasks.isEmpty())
                    assertEquals(90.0, blocker.heading)
                    assertEquals(nearby, frame.unit("nearby").position)
                    previous = blocker.position
                }
                assertTrue(displacedSteps > 1, "$owner/$team never received gradual push")
                assertTrue(context.observe().unit(ship).position.y > 260.0, "$owner/$team did not leave")
                assertEquals(24000L, context.observePlayer(context.world, "p1").balance)
                assertEquals(listOf(ship), context.observe().production!!.units.map { it.unitId })
                println("departure relation=$owner/$team displacedSteps=$displacedSteps blocker=$previous")
            } finally { context.close() }
        }
    }

    @Test
    fun blocked_native_berth_completes_both_orders_then_hands_off_and_accepts_player_takeover() =
        WaterTestScene.withMap { water ->
            // Keep both birth poses exactly coincident, including after the first ship attempts to move.
            val occupied = Position(510.0, 215.999999)
            val map = outletMap(water, lastBankRow = 8)
            val scene = nativeScene(map, rectangle = false,
                UnitSpawn("holder", "p2", "port-marker", occupied, 90.0), workingMarker = true).copy(
                players = listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.LOCAL, "one")))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val frames = Frames(context)
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("anchor-work", "p2", "holder", "seaFactory",
                    Position(710.0, 290.0), "c".repeat(32), context.world)))
                frames.until(100) { it.construction!!.workers.any { worker -> worker.unitId == "holder" && worker.working } }
                // Deliberately reverse lexical nonce order so this is completion order.
                val first = produce(context, "b")
                val second = produce(context, "a")
                val paid = context.observe().economy!!
                assertEquals(18000L, paid.balances.getValue("p1"))
                assertEquals(listOf("b".repeat(32), "a".repeat(32)),
                    paid.queuedProduction.getValue("factory").map { it.unitNonce })
                val firstBirth = frames.until(2650) { Event("unit-produced", first) in it.events }
                val blocked = frames.until(150) { Event("movement-blocked", first) in it.events }
                val secondBirth = frames.until(2650) { Event("unit-produced", second) in it.events }
                val spawn = Position(510.0, 170.0)
                assertTrue(firstBirth.stepIndex < blocked.stepIndex && blocked.stepIndex < secondBirth.stepIndex)
                assertEquals(spawn, firstBirth.unit(first).position)
                assertEquals(spawn, secondBirth.unit(first).position)
                assertEquals(spawn, secondBirth.unit(second).position)
                assertEquals(occupied, secondBirth.unit("holder").position)
                assertEquals(18000L, secondBirth.economy!!.balances.getValue("p1"))
                assertTrue(secondBirth.economy.queuedProduction.getValue("factory").isEmpty())
                repeat(450) {
                    val frame = frames.next()
                    assertEquals(spawn, frame.unit(first).position)
                    assertEquals(spawn, frame.unit(second).position)
                    assertTrue(frame.construction!!.workers.single { it.unitId == "holder" }.working)
                    assertEquals(occupied, frame.unit("holder").position)
                    for (ship in listOf(first, second)) assertEquals(Position(509.0, 285.0), frame.unit(ship).moveTarget)
                }
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-work", "p2", "holder", context.world)))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("back-away", "p2", "holder",
                    Position(510.0, 348.0), context.world)))
                val granted = frames.until(1400) { it.unit(first).position.y > 176.0 }
                assertEquals(spawn, granted.unit(second).position)
                frames.until(1000) { it.unit("holder").position.y > 333.0 }
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("open-berth", "p2", "holder",
                    Position(600.0, 230.0), context.world)))
                val outside = frames.until(1200) { it.unit(first).position.y > 265.0 }
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("clear-exit", "p1", first,
                    Position(600.0, 345.0), context.world)))
                val following = frames.until(1000) { it.unit(second).position.y > 185.0 }
                assertEquals(Position(509.0, 285.0), following.unit(second).moveTarget)
                val manual = Position(511.0, 348.0)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("take-over", "p1", second, manual, context.world)))
                assertEquals("take-over", context.observe().unit(second).tasks.single().actionId)
                frames.until(300) { it.unit(second).moveTarget == manual }
                val arrived = frames.until(2300) { Event("movement-arrived", second) in it.events }
                assertTrue(arrived.unit(second).position.y > 330.0)
                assertEquals(18000L, arrived.economy!!.balances.getValue("p1"))
                assertEquals(setOf(first, second), arrived.production!!.units.map { it.unitId }.toSet())
                println("Paid births ${firstBirth.stepIndex}, ${secondBirth.stepIndex}; blocked from ${blocked.stepIndex}; first moves ${granted.stepIndex}, outside ${outside.stepIndex}; second follows ${following.stepIndex}, manual arrival ${arrived.stepIndex}")
            } finally {
                context.close()
            }
        }

    @Test
    fun a_building_sealing_the_outlet_does_not_pause_or_duplicate_paid_production() = WaterTestScene.withMap { water ->
        val site = Position(510.0, 290.0)
        val scene = nativeScene(outletMap(water, lastBankRow = 13), rectangle = false,
            UnitSpawn("wall", "p2", "seaFactory", site, 0.0)).copy(
            players = listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.LOCAL, "one")))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val first = produce(context, "b")
            val second = produce(context, "a")
            val frames = Frames(context)
            val births = mutableListOf<String>()
            repeat(5600) {
                val frame = frames.next()
                births += frame.events.filter { it.kind == "unit-produced" }.map { checkNotNull(it.unitId) }
                assertEquals(site, frame.unit("wall").position)
                for (ship in frame.units.filter { it.unitId == first || it.unitId == second }) {
                    assertTrue(ship.position.y + 40.0 <= 270.0 + 1e-8, "ship crossed building: $ship")
                    assertEquals(Position(509.0, 285.0), ship.moveTarget)
                }
            }
            val end = context.observe()
            assertEquals(listOf(first, second), births)
            assertEquals(setOf(first, second), end.production!!.units.map { it.unitId }.toSet())
            assertTrue(end.economy!!.queuedProduction.getValue("factory").isEmpty())
            assertEquals(18000L, end.economy.balances.getValue("p1"))
            println("Building sealed outlet: two births, no duplicate charges through ${frames.step}; positions=${end.units.map { it.unitId to it.position }}")
        } finally { context.close() }
    }

    @Test
    fun farther_out_departure_precedes_an_older_ship_still_inside_the_outlet() = WaterTestScene.withMap { water ->
        val scene = nativeScene(outletMap(water, lastBankRow = 13), rectangle = false,
            UnitSpawn("holder", "p2", "port-marker", Position(510.0, 209.0), 90.0), markerRadius = 40.0,
            markerMass = 100000000.0)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val inner = produce(context, "b", "attackSubmarine")
            val outer = produce(context, "a")
            val frames = Frames(context)
            val innerBirth = frames.until(1200) { Event("unit-produced", inner) in it.events }
            assertIs<ActionResult.Accepted>(context.submit(SubmarineDepthAction("surface", "p1", inner, true, context.world)))
            val outerBirth = frames.until(2600) { Event("unit-produced", outer) in it.events }
            assertTrue(outerBirth.unit(inner).position.y < outerBirth.unit(outer).position.y,
                "older=${outerBirth.unit(inner)}, newer=${outerBirth.unit(outer)}")
            assertTrue(outerBirth.unit(inner).height >= -1.0)
            assertEquals(Position(509.0, 285.0), outerBirth.unit(inner).moveTarget)
            context.enableMotionDiagnostics(context.world)
            val nextFrame = frames.next()
            val samples = context.drainMotionDiagnostics(context.world).map { it.unitId }
            assertTrue(outer in samples)
            assertFalse(inner in samples)
            assertEquals(outerBirth.unit(inner).position, nextFrame.unit(inner).position)
            println("Outside priority: first birth ${innerBirth.stepIndex} at ${outerBirth.unit(inner).position}; later birth ${outerBirth.stepIndex} at ${outerBirth.unit(outer).position}; integrated ${nextFrame.stepIndex}: $samples")
        } finally {
            context.close()
        }
    }

    @Test
    fun real_factory_death_refunds_only_the_paid_order_still_pending() = WaterTestScene.withMap { map ->
        val scene = nativeScene(map, rectangle = false,
            UnitSpawn("attacker", "p2", "heavyBattleship", Position(750.0, 350.0), 315.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val produced = produce(context, "b")
            val pending = produce(context, "a")
            val frames = Frames(context)
            val birth = frames.until(2600) { Event("unit-produced", produced) in it.events }
            assertEquals(18000L, birth.economy!!.balances.getValue("p1"))
            assertEquals("a".repeat(32), birth.economy.queuedProduction.getValue("factory").single().unitNonce)
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack-factory", "p2", "attacker", "factory", context.world)))
            val death = frames.until(1600) { !it.unit("factory").alive }
            val refund = Event("production-refunded", "factory")
            assertEquals(1, death.events.count { it == refund })
            assertEquals(24000L, death.economy!!.balances.getValue("p1"))
            assertTrue(death.economy.queuedProduction.getValue("factory").isEmpty())
            assertEquals(listOf(produced), death.production!!.units.map { it.unitId })
            repeat(5000 - frames.step.toInt() + 60) {
                val frame = frames.next()
                assertTrue(refund !in frame.events && frame.units.none { it.unitId == pending })
            }
            assertEquals(24000L, context.observe().economy!!.balances.getValue("p1"))
            println("Factory birth ${birth.stepIndex}, death ${death.stepIndex}, refund 6000 and no second birth through step ${frames.step - 1}")
        } finally {
            context.close()
        }
    }

    private fun outletMap(water: MapDefinition, lastBankRow: Int): MapDefinition {
        val banks = (20 until water.widthTiles).flatMap { column ->
            if (column in 23..27) emptyList() else (0..lastBankRow).map { column to it }
        }.toSet()
        return MapDefinition(water.widthTiles, water.heightTiles, water.tileWidth, water.tileHeight,
            water.tileSets, water.groundGids, water.blocking, banks)
    }

    private fun nativeScene(map: MapDefinition, rectangle: Boolean, vararg additional: UnitSpawn,
                            markerRadius: Double = 6.0, markerMass: Double = 1000.0,
                            workingMarker: Boolean = false): Scenario {
        val base = SeaFactoryScene.scenario(map, "json", "seaFactoryT2")
        val original = WaterTestScene.unit(MovementType.WATER)
        val marker = UnitDefinition("port-marker", if (workingMarker) "AuthoredRoleUnit" else original.className,
            original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed,
            movementCollision = MovementCollisionShape.Circle(markerRadius), mass = markerMass,
            durability = base.unitDefinitions.getValue("seaFactoryT2").durability.takeIf { workingMarker },
            builder = if (workingMarker) com.rustedwarfare.reforge.core.content.BuilderProfile(
                listOf("seaFactory"), range = 250f, buildSpeedMultiplier = .01f) else null)
        val definitions = if (rectangle) {
            val inputs = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
            assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                inputs.resolve("phase-seven-single-ship/json/package"))).value.units
        } else base.unitDefinitions
        val spawns = base.units + additional
        return base.copy(unitDefinitions = definitions + (marker.id to marker),
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)), units = spawns,
            combatContract = CombatContract(12345, -1, spawns.mapIndexed { i, unit -> unit.unitId to i + 8L }.toMap(), spawns.size + 8L),
            economyContract = EconomyContract(mapOf("p1" to 30000L, "p2" to 1000L)))
    }

    private fun produce(context: SimulationContext, digit: String, type: String = "heavyBattleship"): String {
        val nonce = digit.repeat(32)
        assertIs<ActionResult.Accepted>(context.submit(ProduceAction("pay-$digit", "p1", "factory", type, nonce, context.world)))
        return "produced:p1:$nonce"
    }

    private class Frames(private val context: SimulationContext) {
        var step = 0L
            private set
        fun next(): Observation = context.advance(Step(step++, 1.0, "sim-tick"))
        fun until(limit: Int, matches: (Observation) -> Boolean): Observation {
            repeat(limit) {
                val frame = next()
                if (matches(frame)) return frame
            }
            val units = context.observe().units.map { "${it.unitId} ${it.position} -> ${it.moveTarget}" }
            error("Expected state not reached within $limit steps, next step=$step, units=$units")
        }
    }

    private fun Observation.unit(id: String): UnitObservation = units.single { it.unitId == id }
}
