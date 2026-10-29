package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MaintenanceTransactionTest {
    @Test fun a_late_overflow_restores_two_full_worker_queues_future_capacity_writes_and_scheduled_production_nonces() {
        val base = ControlledScenario.load(generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input"))
        val spawns = listOf(UnitSpawn("a-worker", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("b-worker", "p1", "builder", Position(120.0, 210.0), 90.0),
            UnitSpawn("c-worker", "p1", "builder", Position(380.0, 170.0), 90.0),
            UnitSpawn("m-target", "p1", "landFactory", Position(190.0, 170.0), 0.0),
            UnitSpawn("z-factory", "p1", "landFactory", Position(450.0, 170.0), 0.0))
        val scene = base.copy(units = spawns, combatContract = CombatContract(1, -1,
            spawns.mapIndexed { index, spawn -> spawn.unitId to index + 1L }.toMap(), Long.MAX_VALUE - 1))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context }
        var step = 0L
        fun tickBoth() {
            contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")); it.observePlayer(it.world, "p1") }
            step++
        }
        fun command(order: (SimulationContext) -> Action) = contexts.forEach { assertIs<ActionResult.Accepted>(it.submit(order(it))) }
        fun state(c: SimulationContext): List<Any?> {
            val host = c.observe()
            val player = c.observePlayer(c.world, "p1")
            return listOf(host.fields(), host.reclaimedUnitIds, host.combat?.nextObjectId,
                host.economy?.preciseBalances, host.economy?.queuedProduction, host.production?.units,
                host.upgrades?.queues, player.fields())
        }
        try {
            command { ProduceAction("use-last-valid-id", "p1", "z-factory", "builder", "0".repeat(32), it.world) }
            while (contexts[0].observe().production!!.units.isEmpty() && step < 700) tickBoth()
            assertEquals(Long.MAX_VALUE, contexts[0].observe().combat!!.nextObjectId)
            val existingProduct = contexts[0].observe().production!!.units.single().unitId
            command { MoveAction("clear-product", "p1", existingProduct, Position(510.0, 290.0), it.world) }
            command { ReclaimBuildingAction("weaken", "p1", "a-worker", "m-target", it.world) }
            val weakenDeadline = step + 1500
            while (contexts[0].observe().units.single { it.unitId == "m-target" }.health!! > 1.02 && step < weakenDeadline) tickBoth()
            assertTrue(contexts[0].observe().units.single { it.unitId == "m-target" }.health!! in 0.0..1.02)
            command { StopAction("pause", "p1", "a-worker", it.world) }
            command { ProduceAction("late-produce", "p1", "z-factory", "builder", "1".repeat(32), it.world) }
            repeat(401) { tickBoth() }
            assertTrue(contexts[0].observePlayer(contexts[0].world, "p1").factoryQueues
                .single { it.factoryId == "z-factory" }.items.single().progress in .801f.. .803f)
            for (worker in listOf("a-worker", "b-worker")) {
                command { ReclaimBuildingAction("terminal-$worker", "p1", worker, "m-target", it.world) }
                repeat(28) { tail -> command { PatrolAction("tail-$worker-$tail", "p1", worker, Position(450.0, 290.0), true, it.world) } }
            }
            repeat(99) { tickBoth() }
            for (worker in listOf("a-worker", "b-worker")) {
                assertEquals(99f, contexts[0].observe().construction!!.workers.single { it.unitId == worker }.elapsed)
                assertEquals(29, contexts[0].observe().units.single { it.unitId == worker }.tasks.size)
                command { ReclaimBuildingAction("future-full-$worker", "p1", worker, "m-target", it.world, step, true) }
            }
            command { ProduceAction("future-refund-pending", "p1", "m-target", "builder", "2".repeat(32), it.world, step) }
            val reuseNonce = "3".repeat(32)
            command { BuildAction("future-reuse", "p1", "c-worker", "landFactory", Position(450.0, 170.0), reuseNonce, it.world, step) }
            val failed = contexts[0]
            val before = failed.observe()
            val playerBefore = failed.observePlayer(failed.world, "p1")
            val beforeState = state(failed)
            assertEquals(750.0, playerBefore.credits)
            assertFailsWith<ArithmeticException> { failed.advance(Step(step, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(playerBefore, failed.observePlayer(failed.world, "p1"))
            assertEquals(beforeState, state(failed))
            assertEquals(state(contexts[1]), state(failed))
            command { CancelProductionAction("correct-late", "p1", "z-factory", "builder", it.world) }
            tickBoth()
            assertEquals(state(contexts[1]), state(failed))
            assertEquals(listOf("m-target"), failed.observe().reclaimedUnitIds)
            assertEquals(1810.0000083446503, failed.observePlayer(failed.world, "p1").credits)
            assertEquals(1, failed.observe().events.count { it.kind == "building-reclaimed" })
            assertEquals(2, failed.observe().events.count { it.kind == "construction-queue-full" })
            assertEquals(listOf(existingProduct), failed.observe().production!!.units.map { it.unitId })
            for (worker in listOf("a-worker", "b-worker")) {
                val old = before.units.single { it.unitId == worker }
                val now = failed.observe().units.single { it.unitId == worker }
                assertEquals(old.tasks.drop(1), now.tasks)
                assertEquals(old.position, now.position, "The released patrol starts on the following update")
                assertEquals(28, now.tasks.size)
            }
            val reuse = failed.observe().units.single { it.unitId == "c-worker" }.tasks.single()
            assertEquals(UnitTaskKind.CONSTRUCT, reuse.kind)
            assertEquals("z-factory", reuse.targetUnitId)
            assertEquals(1, failed.observe().actionResults.count { it.actionId == "future-reuse" && it is ActionResult.Accepted })
            assertEquals(1, failed.observe().actionResults.count { it.actionId == "future-refund-pending" && it is ActionResult.Accepted })
            command { StopAction("stop-reuse", "p1", "c-worker", it.world) }
            command { BuildAction("nonce-replay", "p1", "c-worker", "landFactory", Position(450.0, 170.0), reuseNonce, it.world) }
            tickBoth()
            assertEquals(state(contexts[1]), state(failed))
            assertEquals("z-factory", failed.observe().units.single { it.unitId == "c-worker" }.tasks.single().targetUnitId,
                "Reusing an existing project never consumes the building nonce")
            assertTrue(failed.observe().events.none { it.kind == "construction-unavailable" && it.unitId == "c-worker" })
            assertEquals(1810.0000083446503, failed.observePlayer(failed.world, "p1").credits)
            contexts.forEach { c ->
                assertEquals("duplicate-unit-nonce", assertIs<ActionResult.Rejected>(c.submit(
                    ProduceAction("used-production-nonce", "p1", "z-factory", "builder", "2".repeat(32), c.world))).error.code)
            }
            assertEquals(state(contexts[1]), state(failed))
            assertEquals(29, before.units.single { it.unitId == "a-worker" }.tasks.size)
            assertTrue(before.units.any { it.unitId == "m-target" && it.alive })
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun a_late_real_production_overflow_rolls_back_the_reclaim_refund_queues_target_and_events_then_retries_once() {
        val base = ControlledScenario.load(generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-005/20261029-implementation-01/input"))
        val spawns = listOf(UnitSpawn("a-worker", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("m-target", "p1", "landFactory", Position(190.0, 170.0), 0.0),
            UnitSpawn("z-factory", "p1", "landFactory", Position(450.0, 170.0), 0.0))
        val scene = base.copy(units = spawns,
            combatContract = CombatContract(1, -1, mapOf("a-worker" to 1L, "m-target" to 2L, "z-factory" to 3L), Long.MAX_VALUE - 1))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context }
        var step = 0L
        fun tickBoth() { contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }; step++ }
        fun command(order: (SimulationContext) -> Action) = contexts.forEach { assertIs<ActionResult.Accepted>(it.submit(order(it))) }
        fun state(c: SimulationContext): List<Any?> {
            val v = c.observe()
            return listOf(v.fields(), v.reclaimedUnitIds, v.combat?.nextObjectId, v.economy?.preciseBalances,
                v.economy?.queuedProduction, v.upgrades?.queues, v.production?.units)
        }
        try {
            command { ProduceAction("use-last-valid-id", "p1", "z-factory", "builder", "0".repeat(32), it.world) }
            while (contexts[0].observe().production!!.units.isEmpty() && step < 700) tickBoth()
            assertEquals(Long.MAX_VALUE, contexts[0].observe().combat!!.nextObjectId)
            val existingProduct = contexts[0].observe().production!!.units.single().unitId
            command { MoveAction("clear-first-product", "p1", existingProduct, Position(510.0, 290.0), it.world) }
            val weakenDeadline = step + 1500
            command { ReclaimBuildingAction("weaken", "p1", "a-worker", "m-target", it.world) }
            while (contexts[0].observe().units.single { it.unitId == "m-target" }.health!! > 1.02 && step < weakenDeadline) tickBoth()
            assertTrue(contexts[0].observe().units.single { it.unitId == "m-target" }.health!! in 0.0..1.02)
            command { StopAction("pause", "p1", "a-worker", it.world) }
            command { ProduceAction("late-produce", "p1", "z-factory", "builder", "1".repeat(32), it.world) }
            repeat(401) { tickBoth() }
            val head = contexts[0].observePlayer(contexts[0].world, "p1").factoryQueues.single { it.factoryId == "z-factory" }.items.single()
            assertTrue(head.progress in .801f.. .803f)
            command { ProduceAction("refund-pending", "p1", "m-target", "builder", "2".repeat(32), it.world) }
            command { ReclaimBuildingAction("terminal", "p1", "a-worker", "m-target", it.world) }
            repeat(99) { tickBoth() }
            assertEquals(99f, contexts[0].observe().construction!!.workers.single { it.unitId == "a-worker" }.elapsed)
            assertEquals(250.0, contexts[0].observePlayer(contexts[0].world, "p1").credits)
            val failed = contexts[0]
            val before = failed.observe()
            val playerBefore = failed.observePlayer(failed.world, "p1")
            assertFailsWith<ArithmeticException> { failed.advance(Step(step, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(playerBefore, failed.observePlayer(failed.world, "p1"))
            assertEquals(emptyList(), failed.observe().reclaimedUnitIds)
            assertEquals(state(contexts[1]), state(failed))
            command { CancelProductionAction("correct-late", "p1", "z-factory", "builder", it.world) }
            tickBoth()
            assertEquals(state(contexts[1]), state(failed))
            assertTrue(failed.observe().units.none { it.unitId == "m-target" })
            assertEquals(listOf("m-target"), failed.observe().reclaimedUnitIds)
            assertEquals(1810.0000083446503, failed.observePlayer(failed.world, "p1").credits)
            assertEquals(1, failed.observe().events.count { it.kind == "building-reclaimed" })
            assertEquals(listOf(existingProduct), failed.observe().production!!.units.map { it.unitId })
            command { MoveAction("surviving-product", "p1", existingProduct, Position(510.0, 330.0), it.world) }
            tickBoth()
            assertEquals(state(contexts[1]), state(failed))
            assertEquals(1810.0000083446503, failed.observePlayer(failed.world, "p1").credits)
            assertTrue(failed.observe().units.single { it.unitId == existingProduct }.alive)
            assertTrue(before.units.any { it.unitId == "m-target" && it.alive })
        } finally { contexts.forEach { it.close() } }
    }
}
