package com.rustedwarfare.reforge.core.sim

import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AaBeamEnergyTest {
    @TempDir lateinit var temporary: Path

    @Test fun repeated_real_shots_exhaust_energy_then_wait_for_full_recharge_before_resuming() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(AaBeamScene.scenario(map, AaBeamScene.definition(temporary), listOf(
            UnitSpawn("aa", "p1", "aaBeamGunship", Position(150.0, 150.0), 90.0),
            UnitSpawn("enemy", "p2", "aaBeamGunship", Position(350.0, 150.0), 270.0))))
        try {
            val firedAt = mutableListOf<Int>()
            repeat(260) { tick ->
                val frame = context.advance(Step(tick.toLong(), 1.0, "sim-tick"))
                if (frame.events.any { it.kind == "projectile-fired" && it.unitId == "aa" }) firedAt += tick
            }
            assertTrue(firedAt.count { it < 100 } in 10..14, "Shot ticks: $firedAt")
            assertTrue(firedAt.zipWithNext().any { (a, b) -> b - a > 100 }, "No full recharge gap: $firedAt")
            assertTrue(firedAt.any { it > 180 }, "Firing must resume after recharge: $firedAt")
        } finally { context.close() }
    }

    @Test fun low_energy_locks_before_zero_and_only_a_full_recharge_unlocks_the_next_paid_shot() = WaterTestScene.withMap { map ->
        val scene = AaBeamScene.scenario(map, AaBeamScene.definition(temporary), listOf(
            UnitSpawn("aa", "p1", "aaBeamGunship", Position(150.0, 150.0), 90.0),
            UnitSpawn("enemy", "p2", "aaBeamGunship", Position(350.0, 150.0), 270.0)))
        val context = AaBeamScene.open(scene)
        try {
            var tick = 0L
            var shots = 0
            var state = context.observe().units.single { it.unitId == "aa" }.energy!!
            while (!state.recharging && tick < 300L) {
                val frame = context.advance(Step(tick++, 1.0, "sim-tick"))
                shots += frame.events.count { it.kind == "projectile-fired" && it.unitId == "aa" }
                state = frame.units.single { it.unitId == "aa" }.energy!!
            }
            assertEquals(13, shots)
            assertTrue(state.recharging)
            assertEquals(.15f, state.current, .00001f)
            var previous = state
            while (state.recharging && tick < 500L) {
                val frame = context.advance(Step(tick++, 1.0, "sim-tick"))
                assertTrue(frame.events.none { it.kind == "projectile-fired" && it.unitId == "aa" })
                state = frame.units.single { it.unitId == "aa" }.energy!!
                assertEquals(minOf(6f, previous.current + .05f), state.current)
                previous = state
            }
            assertEquals(EnergyObservation(6f, 6f, false), state)
            val resumed = context.advance(Step(tick, 1.0, "sim-tick"))
            assertTrue(resumed.events.any { it.kind == "projectile-fired" && it.unitId == "aa" })
            assertEquals(5.51f, resumed.units.single { it.unitId == "aa" }.energy!!.current)
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(scene))
            assertEquals(EnergyObservation(6f, 6f, false), context.observe().units.single { it.unitId == "aa" }.energy)
            assertTrue(context.observe().projectiles.isEmpty())
        } finally { context.close() }
    }

    @Test fun first_beam_allocation_failure_rolls_back_observations_and_reopen_resets_history() = WaterTestScene.withMap { map ->
        val base = AaBeamScene.scenario(map, AaBeamScene.definition(temporary), listOf(
            UnitSpawn("aa", "p1", "aaBeamGunship", Position(150.0, 150.0), 90.0),
            UnitSpawn("enemy", "p2", "aaBeamGunship", Position(350.0, 150.0), 270.0)))
        val context = AaBeamScene.open(base.copy(combatContract = base.combatContract!!.copy(nextObjectId = Long.MAX_VALUE - 1)))
        try {
            var tick = 0L
            var failed = false
            while (!failed && tick < 200L) {
                val before = context.observe()
                val player = context.observePlayer(context.world, "p1")
                try { context.advance(Step(tick, 1.0, "sim-tick")); tick++ }
                catch (overflow: ArithmeticException) {
                    failed = true
                    assertSame(before, context.observe())
                    assertSame(player, context.observePlayer(context.world, "p1"))
                    assertEquals(EnergyObservation(6f, 6f, false), before.units.single { it.unitId == "aa" }.energy)
                    assertEquals(listOf("enemy"), before.projectiles.map { it.sourceUnitId })
                    assertFailsWith<ArithmeticException> { context.advance(Step(tick, 1.0, "sim-tick")) }
                    assertSame(before, context.observe())
                }
            }
            assertTrue(failed)
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(base))
            assertEquals(0L, context.observe().stepIndex)
            assertTrue(context.observePlayer(context.world, "p1").projectiles.isEmpty())
        } finally { context.close() }
    }

    @Test fun allocation_failure_on_the_locking_shot_restores_energy_lock_and_both_observations() = WaterTestScene.withMap { map ->
        val base = AaBeamScene.scenario(map, AaBeamScene.definition(temporary), listOf(
            UnitSpawn("aa", "p1", "aaBeamGunship", Position(150.0, 150.0), 90.0),
            UnitSpawn("enemy", "p2", "aaBeamGunship", Position(350.0, 150.0), 270.0),
            UnitSpawn("zz", "p1", "aaBeamGunship", Position(69.999, 150.0), 90.0)))
        val reference = AaBeamScene.open(base)
        var lockTick = -1L
        try {
            var shots = 0
            for (tick in 0L..200L) {
                val frame = reference.advance(Step(tick, 1.0, "sim-tick"))
                shots += frame.events.count { it.kind == "projectile-fired" && it.unitId == "aa" }
                if (frame.units.single { it.unitId == "aa" }.energy!!.recharging) {
                    assertEquals(13, shots)
                    lockTick = tick
                    break
                }
            }
            assertTrue(lockTick >= 0L)
        } finally { reference.close() }
        fun approach(context: SimulationContext) {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("approach", "p1", "zz",
                Position(100.0, 150.0), context.world)))
        }
        // The third gunship enters range on the locking step, after aa has paid
        // and reused its beam. Only its first beam needs a new object ID.
        val healthy = AaBeamScene.open(base)
        try {
            for (tick in 0L..lockTick) {
                if (tick == lockTick - 1L) approach(healthy)
                val frame = healthy.advance(Step(tick, 1.0, "sim-tick"))
                if (tick < lockTick) assertTrue(frame.events.none { it.kind == "projectile-fired" && it.unitId == "zz" })
                else {
                    assertTrue(frame.units.single { it.unitId == "aa" }.energy!!.recharging)
                    assertEquals(.15f, frame.units.single { it.unitId == "aa" }.energy!!.current, .00001f)
                    assertTrue(frame.events.any { it.kind == "projectile-fired" && it.unitId == "aa" })
                    assertTrue(frame.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == "zz" })
                }
            }
        } finally { healthy.close() }
        val limited = base.copy(combatContract = base.combatContract!!.copy(nextObjectId = Long.MAX_VALUE - 2))
        val context = AaBeamScene.open(limited)
        try {
            for (tick in 0L until lockTick) {
                if (tick == lockTick - 1L) approach(context)
                context.advance(Step(tick, 1.0, "sim-tick"))
            }
            val before = context.observe()
            val player = context.observePlayer(context.world, "p1")
            val energy = before.units.single { it.unitId == "aa" }.energy!!
            assertFalse(energy.recharging)
            assertTrue(energy.current in .5f..1f)
            assertEquals(setOf("aa", "enemy"), before.projectiles.map { it.sourceUnitId }.toSet())
            repeat(2) {
                assertFailsWith<ArithmeticException> { context.advance(Step(lockTick, 1.0, "sim-tick")) }
                assertSame(before, context.observe())
                assertSame(player, context.observePlayer(context.world, "p1"))
                assertEquals(energy, context.observe().units.single { it.unitId == "aa" }.energy)
            }
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(base))
            assertEquals(EnergyObservation(6f, 6f, false), context.observe().units.single { it.unitId == "aa" }.energy)
            assertTrue(context.observe().projectiles.isEmpty())
            assertTrue(context.observePlayer(context.world, "p1").projectiles.isEmpty())
        } finally { context.close() }
    }
}
