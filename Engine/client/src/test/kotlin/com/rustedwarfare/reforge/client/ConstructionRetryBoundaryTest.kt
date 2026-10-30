package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConstructionRetryBoundaryTest {
    @Test fun elapsed_one_thousand_does_not_pop_until_the_next_real_failed_payment_and_preserves_the_tail() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(false))).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "landFactory",
                Position(190.0, 170.0), "1".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(PatrolAction("tail", "p1", "worker", Position(450.0, 290.0), true, context.world)))
            for (step in 0L..998L) context.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(999f, context.observe().construction!!.workers.single().elapsed)
            assertEquals(listOf("build", "tail"), context.observe().units.single().tasks.map { it.actionId })
            for ((step, elapsed) in listOf(999L to 1000f, 1000L to 1001f)) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(elapsed, frame.construction!!.workers.single().elapsed)
                assertEquals(listOf("build", "tail"), frame.units.single().tasks.map { it.actionId })
                assertTrue(frame.events.none { it.kind == "construction-insufficient-funds" })
            }
            val failure = context.advance(Step(1001, 1.0, "sim-tick"))
            assertEquals(1, failure.events.count { it.kind == "construction-insufficient-funds" })
            assertEquals(listOf("tail"), failure.units.single().tasks.map { it.actionId })
            assertEquals(0f, failure.construction!!.workers.single().elapsed)
            assertTrue(failure.construction!!.buildings.isEmpty())
            assertNull(failure.units.single().moveTarget, "Do not execute the tail in the failed-payment pop frame")
            assertEquals(699.0, context.observePlayer(context.world, "p1").credits)
            val next = context.advance(Step(1002, 1.0, "sim-tick"))
            assertEquals("tail", next.units.single().tasks.single().actionId)
            assertNotNull(next.units.single().moveTarget)
        } finally { context.close() }
    }

    @Test fun real_income_cannot_bypass_the_retry_gate_and_the_next_qualified_attempt_charges_once() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(true))).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "landFactory",
                Position(190.0, 170.0), "2".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(PatrolAction("tail", "p1", "worker", Position(450.0, 290.0), true, context.world)))
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            assertTrue(first.events.any { it.kind == "construction-insufficient-funds" })
            assertEquals(16, first.construction!!.clock)
            for (step in 1L..12L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                assertTrue(frame.construction!!.buildings.isEmpty(), "Restored funds alone cannot skip the Q gate")
                assertEquals(listOf("build", "tail"), frame.units.single { it.unitId == "worker" }.tasks.map { it.actionId })
            }
            assertEquals(703.5, context.observePlayer(context.world, "p1").credits)
            val created = context.advance(Step(13, 1.0, "sim-tick"))
            assertEquals(224, created.construction!!.clock)
            assertEquals(1, created.events.count { it.kind == "construction-created" })
            assertEquals(1, created.units.count { it.typeId == "landFactory" })
            assertEquals(3.5, context.observePlayer(context.world, "p1").credits)
            assertEquals(listOf(UnitTaskKind.CONSTRUCT, UnitTaskKind.PATROL),
                created.units.single { it.unitId == "worker" }.tasks.map { it.kind })
            val next = context.advance(Step(14, 1.0, "sim-tick"))
            assertTrue(next.events.none { it.kind == "construction-created" })
            assertEquals(3.5, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    private fun scene(income: Boolean): Scenario {
        val repo = System.getProperty("repo.root")?.let { Path.of(it) } ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val base = ControlledScenario.load((System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/queue") } ?: repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input")))
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        val spawns = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0)) +
            if (income) listOf(UnitSpawn("center", "p1", "commandCenter", Position(70.0, 70.0), 0.0)) else emptyList()
        return base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground }, BlockingGrid(40, 25, BooleanArray(1000))),
            units = spawns, economyContract = EconomyContract(mapOf("p1" to 699L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, spawns.mapIndexed { index, spawn -> spawn.unitId to index + 1L }.toMap(), spawns.size + 1L))
    }
}
