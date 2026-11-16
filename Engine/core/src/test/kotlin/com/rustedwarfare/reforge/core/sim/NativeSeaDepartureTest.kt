package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeSeaDepartureTest {
    @Test
    fun automatic_exit_can_move_the_direct_idle_own_blocker_within_the_existing_limit() =
        WaterTestScene.withMap { map ->
            val direct = Position(510.0, 230.0)
            val nearby = Position(554.0, 224.0)
            val scene = nativeScene(map, rectangle = true,
                UnitSpawn("direct", "p1", "port-marker", direct, 90.0),
                UnitSpawn("nearby", "p1", "port-marker", nearby, 90.0))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val ship = produce(context, "a")
                val ticks = Frames(context)
                val birth = ticks.until(2600) { frame -> Event("unit-produced", ship) in frame.events }
                assertEquals(Position(510.0, 170.0), birth.unit(ship).position)
                val frames = List(510) { ticks.next() }
                val shifted = frames.firstOrNull { it.unit("direct").position != direct }
                val original = frames.last().unit(ship)
                println("Auto exit birth=${birth.stepIndex} shift=${shifted?.stepIndex} berth=${original.position} direct=${frames.last().unit("direct").position}")
                assertNotNull(shifted)
                val newPosition = shifted.unit("direct").position
                assertTrue(abs(newPosition.x - direct.x) in 15.0..20.0)
                val heading = ScreenPlane.displacement(shifted.unit(ship).heading, 1.0)
                val longitudinal = (newPosition.x - direct.x) * heading.x + (newPosition.y - direct.y) * heading.y
                assertEquals(0.0, longitudinal, 1e-8)
                assertTrue(frames.dropWhile { it !== shifted }.all { it.unit("direct").position == newPosition })
                assertTrue(frames.all { it.unit("nearby").position == nearby })
                assertTrue(original.position.y > 260.0)
                assertEquals(24000L, context.observe().economy!!.balances.getValue("p1"))
            } finally {
                context.close()
            }
        }

    @Test
    fun blocked_native_berth_completes_both_orders_then_hands_off_and_accepts_player_takeover() =
        WaterTestScene.withMap { water ->
            // Keep both birth poses exactly coincident, including after the first ship attempts to move.
            val occupied = Position(510.0, 249.999999)
            val map = outletMap(water, lastBankRow = 10)
            val scene = nativeScene(map, rectangle = false,
                UnitSpawn("holder", "p2", "port-marker", occupied, 90.0), markerRadius = 40.0)
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                // Deliberately reverse lexical nonce order so this is completion order.
                val first = produce(context, "b")
                val second = produce(context, "a")
                val paid = context.observe().economy!!
                assertEquals(18000L, paid.balances.getValue("p1"))
                assertEquals(listOf("b".repeat(32), "a".repeat(32)),
                    paid.queuedProduction.getValue("factory").map { it.unitNonce })
                val frames = Frames(context)
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
                    for (ship in listOf(first, second)) assertEquals(Position(509.0, 285.0), frame.unit(ship).moveTarget)
                }
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("back-away", "p2", "holder",
                    Position(510.0, 348.0), context.world)))
                val granted = frames.until(1400) { it.unit(first).position.y > 176.0 }
                assertEquals(spawn, granted.unit(second).position)
                frames.until(1000) { it.unit("holder").position.y > 333.0 }
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("open-berth", "p2", "holder",
                    Position(746.0, 345.0), context.world)))
                val outside = frames.until(1200) { it.unit(first).position.y > 265.0 }
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("clear-exit", "p1", first,
                    Position(635.0, 345.0), context.world)))
                val following = frames.until(1000) { it.unit(second).position.y > 185.0 }
                assertEquals(Position(509.0, 285.0), following.unit(second).moveTarget)
                val manual = Position(511.0, 348.0)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("take-over", "p1", second, manual, context.world)))
                assertEquals(manual, context.observe().unit(second).moveTarget)
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
    fun farther_out_departure_precedes_an_older_ship_still_inside_the_outlet() = WaterTestScene.withMap { water ->
        val scene = nativeScene(outletMap(water, lastBankRow = 13), rectangle = false,
            UnitSpawn("holder", "p2", "port-marker", Position(510.0, 209.0), 90.0), markerRadius = 40.0)
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
                            markerRadius: Double = 6.0): Scenario {
        val base = SeaFactoryScene.scenario(map, "json", "seaFactoryT2")
        val original = WaterTestScene.unit(MovementType.WATER)
        val marker = UnitDefinition("port-marker", original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed,
            movementCollision = MovementCollisionShape.Circle(markerRadius))
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
