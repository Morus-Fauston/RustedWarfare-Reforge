package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SeaFactoryBirthBoundaryTest {
    @Test fun native_speed_thresholds_use_strict_float_comparisons_for_the_birth_protection_timer() = WaterTestScene.withMap { map ->
        for ((speed, timer) in listOf(.75 to 30f, .749 to 60f, .55 to 60f, .549 to 80f)) {
            val base = SeaFactoryScene.scenario(map, tier = "seaFactoryT2")
            val source = base.unitDefinitions.getValue("heavyBattleship")
            // Finite speed instrument; the loaded factory, build metadata, hull and real birth path are retained.
            val product = UnitDefinition(source.id, source.className, source.radius, source.displayRadius, source.totalFrames,
                source.imagePath, source.imageScale, source.movement.copy(moveSpeed = speed), source.retainedUnconsumed,
                source.fogOfWarSightRange, combat = source.combat, build = source.build)
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
                unitDefinitions = base.unitDefinitions + ("heavyBattleship" to product)))).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("pay", "p1", "factory", "heavyBattleship", "a".repeat(32), context.world)))
                var q = 0L
                while (context.observe().production!!.units.isEmpty() && q < 2600L) context.advance(Step(q++, 1.0, "sim-tick"))
                assertEquals(timer, context.observe().production!!.units.single().birthRemaining, "speed=$speed")
                assertEquals(Position(510.0, 170.0), context.observe().units.single { it.typeId == "heavyBattleship" }.position)
                assertEquals(5000L, context.observePlayer(context.world, "p1").balance)
            } finally { context.close() }
        }
    }

    @Test fun an_outside_birth_rolls_back_the_frame_and_cancelling_the_paid_item_allows_same_step_recovery() = WaterTestScene.withMap { map ->
        // A trusted-host preplaced n-footprint fits, but the r40 birth circle would extend beyond the map.
        // Ordinary player construction uses the larger o-footprint and cannot place this site.
        val base = SeaFactoryScene.scenario(map, tier = "seaFactoryT2")
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            units = base.units.map { it.copy(position = Position(510.0, 350.0)) }))).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("pay", "p1", "factory", "heavyBattleship", "a".repeat(32), context.world)))
            var q = 0L
            var before: Observation? = null
            var failure: IllegalArgumentException? = null
            while (q < 2600L) {
                val previous = context.observe()
                try { context.advance(Step(q, 1.0, "sim-tick")); q++ }
                catch (error: IllegalArgumentException) { before = previous; failure = error; break }
            }
            assertTrue(assertNotNull(failure).message.orEmpty().contains("Produced unit collision circle"))
            val old = assertNotNull(before)
            assertSame(old, context.observe())
            assertEquals(5000L, context.observePlayer(context.world, "p1").balance)
            assertEquals(1, old.economy!!.queuedProduction.getValue("factory").size)
            assertEquals(0L, old.production!!.lastBirthSerial)
            assertTrue(old.units.none { it.unitId.startsWith("produced:") })
            assertIs<ActionResult.Accepted>(context.submit(CancelProductionAction("cancel", "p1", "factory", "heavyBattleship", context.world)))
            assertEquals(11000L, context.observePlayer(context.world, "p1").balance)
            assertEquals("duplicate-unit-nonce", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("used", "p1", "factory", "heavyBattleship", "a".repeat(32), context.world))).error.code)
            val recovered = context.advance(Step(q, 1.0, "sim-tick"))
            assertEquals(old.combat!!.nextObjectId, recovered.combat!!.nextObjectId)
            assertEquals(0L, recovered.production!!.lastBirthSerial)
            assertTrue(recovered.economy!!.queuedProduction.getValue("factory").isEmpty())
            assertEquals(1, old.economy.queuedProduction.getValue("factory").size)
        } finally { context.close() }
    }

    @Test fun an_external_dry_wall_blocks_the_native_exit_without_duplicate_birth_or_charge_and_a_legal_move_recovers() = WaterTestScene.withMap { source ->
        val gids = source.groundGids
        for (column in 20..39) gids[13 * 40 + column] = 1
        val map = MapDefinition(40, 20, 20, 20, source.tileSets, gids,
            BlockingGrid(40, 20, BooleanArray(800) { gids[it] == 2 }))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(SeaFactoryScene.scenario(map, tier = "seaFactoryT2"))).context
        try {
            val id = "produced:p1:${"b".repeat(32)}"
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("pay", "p1", "factory", "heavyBattleship", "b".repeat(32), context.world)))
            var q = 0L
            val events = mutableListOf<Event>()
            fun tick() = context.advance(Step(q++, 1.0, "sim-tick")).also { events += it.events }
            while (context.observe().units.none { it.unitId == id } && q < 2600L) tick()
            repeat(450) { tick() }
            val blocked = context.observe()
            assertEquals(1, events.count { it.kind == "unit-produced" && it.unitId == id })
            assertTrue(events.any { it.kind == "path-unreachable" && it.unitId == id })
            assertTrue(blocked.units.single { it.unitId == id }.position.y < 260.0)
            assertEquals(0f, blocked.production!!.units.single().birthRemaining)
            assertEquals(5000L, context.observePlayer(context.world, "p1").balance)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("recover", "p1", id, Position(600.0, 220.0), context.world)))
            repeat(400) { tick() }
            assertTrue(context.observe().units.single { it.unitId == id }.position.x > 590.0)
            assertEquals(1, context.observe().production!!.units.size)
            assertEquals(5000L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
    }
}
