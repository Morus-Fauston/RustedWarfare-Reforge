package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConstructionQTest {
    @Test fun diagnostics_do_not_change_construction_and_close_reopen_discards_the_old_navigation_state() {
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(openScene())).context }
        try {
            contexts[0].enablePathDiagnostics(contexts[0].world)
            for (context in contexts) {
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("head", "p1", "worker", "landFactory", Position(650.0, 170.0), "a".repeat(32), context.world)))
                assertIs<ActionResult.Accepted>(context.submit(PatrolAction("tail", "p1", "worker", Position(170.0, 290.0), true, context.world)))
            }
            for (step in 0L..4L) {
                val actual = contexts[0].advance(Step(step, 1.0, "sim-tick"))
                val expected = contexts[1].advance(Step(step, 1.0, "sim-tick"))
                assertEquals(expected.fields(), actual.fields())
                assertEquals(contexts[1].observePlayer(contexts[1].world, "p1").fields(), contexts[0].observePlayer(contexts[0].world, "p1").fields())
            }
            val context = contexts[0]
            val old = context.observe().construction!!.workers.single()
            assertEquals(1.toByte(), old.singleNodePathCount)
            var prepared = false
            ClientEvidence.NONE.recordWhenEnabled("navigation") { prepared = true; mapOf("sample" to context.observe().fields()) }
            assertFalse(prepared, "The ordinary disabled sink must not build trusted navigation payloads")
            assertEquals(1, context.drainPathDiagnostics(context.world).size)
            val oldWorld = context.world
            context.close()
            assertEquals("closed", assertFailsWith<com.rustedwarfare.reforge.core.path.PathDiagnosticsException> { context.drainPathDiagnostics(oldWorld) }.code)
            assertFailsWith<IllegalStateException> { context.observe() }
            assertIs<InitResult.Accepted>(context.initialize(openScene()))
            assertNotSame(oldWorld, context.world)
            val fresh = context.observe().construction!!.workers.single()
            assertEquals(0.toByte(), fresh.singleNodePathCount)
            assertNull(fresh.logicalRequestX); assertNull(fresh.logicalRequestY)
            assertTrue(fresh.pendingPathNodeCounts.isEmpty() && fresh.consumedPathNodeCounts.isEmpty())
            assertEquals(1.toByte(), old.singleNodePathCount)
            assertEquals("stale-world", assertFailsWith<com.rustedwarfare.reforge.core.path.PathDiagnosticsException> { context.enablePathDiagnostics(oldWorld) }.code)
            assertEquals("not-enabled", assertFailsWith<com.rustedwarfare.reforge.core.path.PathDiagnosticsException> { context.drainPathDiagnostics(context.world) }.code)
            context.enablePathDiagnostics(context.world)
            assertTrue(context.drainPathDiagnostics(context.world).isEmpty())
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun a_real_null_route_after_four_build_acceptances_keeps_both_tasks_and_the_old_count() {
        val base = openScene()
        val tileset = base.map.tileSets.first { 2 in it.blockedTileIds }
        val width = 40; val height = 25
        val blocked = BooleanArray(width * height) { it % width == 27 && it / width !in 13..15 }
        val spawns = listOf(UnitSpawn("f", "p1", "landFactory", Position(110.0, 290.0), 0.0),
            UnitSpawn("b1", "p1", "builder", Position(310.0, 370.0), 0.0),
            UnitSpawn("b2", "p1", "builder", Position(430.0, 370.0), 0.0),
            UnitSpawn("b3", "p1", "builder", Position(490.0, 290.0), 90.0),
            UnitSpawn("r", "p1", "builder", Position(180.0, 290.0), 270.0))
        val scene = base.copy(map = MapDefinition(width, height, 20, 20, base.map.tileSets,
            IntArray(width * height) { if (blocked[it]) tileset.firstGid + 2 else tileset.firstGid }, BlockingGrid(width, height, blocked)),
            units = spawns, economyContract = EconomyContract(mapOf("p1" to 5000L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, spawns.mapIndexed { index, spawn -> spawn.unitId to index + 1L }.toMap(), 6L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var step = 0L
            fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("produce", "p1", "f", "builder", "a".repeat(32), context.world)))
            while (context.observe().production!!.units.isEmpty() && step < 2000) tick()
            val workerId = context.observe().production!!.units.single().unitId
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-birth", "p1", workerId, context.world)))
            repeat(30) { tick() }
            assertEquals(0f, context.observe().production!!.units.single().birthRemaining)
            assertEquals(Position(110.0, 295.0), context.observe().units.single { it.unitId == workerId }.position)
            assertIs<ActionResult.Accepted>(context.submit(ReclaimBuildingAction("weaken", "p1", "r", "f", context.world)))
            val deadline = step + 2000
            while (context.observe().units.single { it.unitId == "f" }.health!! > 1.02 && step < deadline) tick()
            val weakHealth = context.observe().units.single { it.unitId == "f" }.health!!
            assertTrue(weakHealth > 0.0 && weakHealth <= 1.02, "Real reclaim must leave one final hit, without setting health")
            assertIs<ActionResult.Accepted>(context.submit(StopAction("pause-reclaim", "p1", "r", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(ReclaimBuildingAction("last-reclaim", "p1", "r", "f", context.world)))
            repeat(96) { tick() }
            assertEquals(96f, context.observe().construction!!.workers.single { it.unitId == "r" }.elapsed)
            assertEquals(weakHealth, context.observe().units.single { it.unitId == "f" }.health)
            context.enablePathDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("head", "p1", workerId, "landFactory", Position(710.0, 290.0), "b".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("tail", "p1", workerId, "landFactory", Position(710.0, 430.0), "c".repeat(32), context.world, append = true)))
            for (index in 0..2) assertIs<ActionResult.Accepted>(context.submit(BuildAction("block-$index", "p1", "b${index + 1}",
                if (index == 2) "fabricatorT1" else "landFactory", Position(310.0 + index * 120.0, 290.0),
                (index + 1).toString().repeat(32), context.world, atStep = step + index)))
            repeat(4) { index ->
                tick()
                val worker = context.observe().construction!!.workers.single { it.unitId == workerId }
                val searches = context.drainPathDiagnostics(context.world).filter { it.unitId == workerId }
                println("BUILD-Q4-NULL setup=$index worker=$worker searches=$searches factoryPresent=${context.observe().units.any { it.unitId == "f" }}")
                assertEquals(1, searches.size)
                assertEquals(1, searches.single().pathPointCount)
                assertEquals(com.rustedwarfare.reforge.core.path.PathSearchOutcome.FOUND, searches.single().outcome)
                assertEquals(index.toByte(), worker.qBeforeDispatch)
                assertEquals((index + 1).toByte(), worker.singleNodePathCount)
                assertEquals(listOf(1), worker.consumedPathNodeCounts)
                assertEquals(listOf("head", "tail"), context.observePlayer(context.world, "p1").units.single { it.unitId == workerId }.tasks.map { it.actionId })
                assertEquals(index != 3, context.observe().units.any { it.unitId == "f" })
            }
            assertEquals(1460.0000083446503, context.observePlayer(context.world, "p1").credits)
            repeat(4) { index ->
                tick()
                val worker = context.observe().construction!!.workers.single { it.unitId == workerId }
                val view = context.observePlayer(context.world, "p1").units.single { it.unitId == workerId }
                val searches = context.drainPathDiagnostics(context.world).filter { it.unitId == workerId }
                println("BUILD-Q4-NULL blocked=$index worker=$worker searches=$searches tasks=${view.tasks}")
                assertTrue(searches.isNotEmpty())
                assertTrue(searches.all { it.outcome == com.rustedwarfare.reforge.core.path.PathSearchOutcome.UNREACHABLE && it.pathPointCount == 0 })
                if (index == 0) assertTrue(searches.any { it.reason == com.rustedwarfare.reforge.core.path.PathSearchReason.ROUTE_REPAIR })
                assertEquals(listOf("head", "tail"), view.tasks.map { it.actionId }, "A real null result cannot let the old q gate pop BUILD")
                assertEquals(4.toByte(), worker.qBeforeDispatch)
                assertEquals(4.toByte(), worker.singleNodePathCount)
                assertTrue(worker.movementRequested)
                assertEquals(710f, worker.logicalRequestX); assertEquals(290f, worker.logicalRequestY)
                assertTrue(worker.pendingPathNodeCounts.isEmpty() && worker.consumedPathNodeCounts.isEmpty())
                assertNull(view.moveTarget); assertTrue(view.pathPoints.isEmpty())
            }
        } finally { context.close() }
    }

    @Test fun float_target_changes_stop_replacement_and_arrival_reset_but_append_preserves_the_request() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(openScene())).context
        fun state() = context.observe().construction!!.workers.single()
        fun move(id: String, target: Position) = assertIs<ActionResult.Accepted>(context.submit(MoveAction(id, "p1", "worker", target, context.world)))
        try {
            move("first", Position(650.0, 170.0))
            context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(1.toByte(), state().singleNodePathCount)
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("append", "p1", "worker", "landFactory", Position(650.0, 290.0), "a".repeat(32), context.world, append = true)))
            assertEquals(1.toByte(), state().singleNodePathCount)
            assertEquals(650f, state().logicalRequestX); assertEquals(170f, state().logicalRequestY)
            move("same-float", Position(650.000001, 170.0))
            assertEquals(1.toByte(), state().singleNodePathCount)
            context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(2.toByte(), state().singleNodePathCount)
            move("different-float", Position(651.0, 170.0))
            assertEquals(0.toByte(), state().singleNodePathCount)
            assertEquals(listOf(1), state().pendingPathNodeCounts)
            context.advance(Step(2, 1.0, "sim-tick"))
            assertEquals(1.toByte(), state().singleNodePathCount)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "worker", context.world)))
            assertEquals(0.toByte(), state().singleNodePathCount)
            assertNull(state().logicalRequestX); assertNull(state().logicalRequestY)
            assertTrue(state().pendingPathNodeCounts.isEmpty())
            context.advance(Step(3, 1.0, "sim-tick"))
            assertFalse(state().movementRequested)
            assertEquals(0.toByte(), state().singleNodePathCount)
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "landFactory", Position(650.0, 170.0), "b".repeat(32), context.world)))
            context.advance(Step(4, 1.0, "sim-tick"))
            assertEquals(1.toByte(), state().singleNodePathCount)
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("replace", "p1", "worker", "landFactory", Position(650.0, 290.0), "c".repeat(32), context.world)))
            assertEquals(0.toByte(), state().singleNodePathCount)
            assertNull(state().logicalRequestX)
            val current = context.observePlayer(context.world, "p1").units.single().position
            move("arrive", Position(current.x + 2.0, current.y))
            context.advance(Step(5, 1.0, "sim-tick"))
            assertEquals(0.toByte(), state().singleNodePathCount)
            assertNull(state().logicalRequestX)
            assertTrue(context.observePlayer(context.world, "p1").events.any { it.kind == "movement-arrived" })
        } finally { context.close() }
    }

    @Test fun a_real_second_shot_failure_rolls_back_already_consumed_pending_paths_before_same_step_retry() {
        val base = openScene()
        val tank = base.unitDefinitions.getValue("c_tank")
        val witness = com.rustedwarfare.reforge.core.content.UnitDefinition("witness", tank.className, tank.radius,
            tank.displayRadius, tank.totalFrames, tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed,
            tank.fogOfWarSightRange, checkNotNull(tank.combat).copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + ("witness" to witness),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("a", "p1", "c_tank", Position(110.0, 210.0), 90.0),
                UnitSpawn("aa", "p1", "builder", Position(110.0, 110.0), 90.0),
                UnitSpawn("b", "p2", "c_tank", Position(220.0, 210.0), 270.0),
                UnitSpawn("c", "p1", "witness", Position(220.0, 330.0), 180.0)),
            automaticVision = com.rustedwarfare.reforge.core.knowledge.KnowledgeRules(
                com.rustedwarfare.reforge.core.knowledge.TerrainRetention.MEMORY,
                com.rustedwarfare.reforge.core.knowledge.PathInformationSource.PLAYER_KNOWLEDGE, false),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "aa" to 9L, "b" to 10L, "c" to 11L), Long.MAX_VALUE - 1))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context }
        try {
            for (context in contexts) {
                context.enablePathDiagnostics(context.world)
                repeat(128) { index -> assertIs<ActionResult.Accepted>(context.submit(MoveAction("pending-$index", "p1", "aa", Position(650.0, 110.0), context.world))) }
                assertEquals(128, context.drainPathDiagnostics(context.world).size)
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("near", "p2", "b", "a", context.world)))
                assertIs<ActionResult.Accepted>(context.submit(SelectAction("scheduled", "p1", "c", context.world, 0)))
            }
            val failed = contexts[0]; val control = contexts[1]
            val before = failed.observe()
            val playerBefore = failed.observePlayer(failed.world, "p1")
            control.observe()
            control.observePlayer(control.world, "p1")
            val pending = before.construction!!.workers.single()
            assertEquals(List(128) { 1 }, pending.pendingPathNodeCounts)
            assertEquals(0.toByte(), pending.singleNodePathCount)
            assertFailsWith<ArithmeticException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(playerBefore, failed.observePlayer(failed.world, "p1"))
            assertEquals(List(128) { 1 }, failed.observe().construction!!.workers.single().pendingPathNodeCounts)
            assertEquals(0.toByte(), failed.observe().construction!!.workers.single().singleNodePathCount)
            assertTrue(failed.drainPathDiagnostics(failed.world).isEmpty(), "The failed candidate must not publish new path samples")
            for (context in contexts)
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("side", "p2", "b", "c", context.world)))
            val actual = failed.advance(Step(0, 1.0, "sim-tick"))
            val expected = control.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(expected.fields(), actual.fields())
            assertEquals(expected.combat, actual.combat)
            assertEquals(listOf(Long.MAX_VALUE - 1), actual.projectiles.map { it.objectId })
            assertEquals("a", actual.projectiles.single().sourceUnitId)
            val worker = actual.construction!!.workers.single()
            assertEquals(0.toByte(), worker.qBeforeDispatch)
            assertEquals((-128).toByte(), worker.singleNodePathCount, "Retry must consume all pending real acceptances exactly once")
            assertEquals(List(128) { 1 }, worker.consumedPathNodeCounts)
            assertTrue(worker.pendingPathNodeCounts.isEmpty())
            assertEquals(List(128) { 1 }, pending.pendingPathNodeCounts)
            assertEquals(control.observePlayer(control.world, "p1").fields(), failed.observePlayer(failed.world, "p1").fields())
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun closing_a_real_corridor_preserves_the_build_and_prior_count_when_the_solver_returns_null() {
        val base = openScene()
        val tileset = base.map.tileSets.first { 2 in it.blockedTileIds }
        val width = 40; val height = 25
        val blocked = BooleanArray(width * height) { it % width == 20 && it / width !in 4..6 }
        val scene = base.copy(map = MapDefinition(width, height, 20, 20, base.map.tileSets,
            IntArray(width * height) { if (blocked[it]) tileset.firstGid + 2 else tileset.firstGid }, BlockingGrid(width, height, blocked)),
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(110.0, 110.0), 90.0),
                UnitSpawn("blocker", "p1", "builder", Position(350.0, 110.0), 90.0)),
            economyContract = EconomyContract(mapOf("p1" to 5000L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "blocker" to 2L), 3L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.enablePathDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("head", "p1", "worker", "landFactory", Position(710.0, 110.0), "a".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(PatrolAction("tail", "p1", "worker", Position(110.0, 290.0), true, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("close", "p1", "blocker", "fabricatorT1", Position(410.0, 110.0), "b".repeat(32), context.world, atStep = 1)))
            context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(1, context.drainPathDiagnostics(context.world).single { it.unitId == "worker" }.pathPointCount)
            assertEquals(1.toByte(), context.observe().construction!!.workers.single { it.unitId == "worker" }.singleNodePathCount)
            context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(1, context.observe().construction!!.buildings.size)
            context.drainPathDiagnostics(context.world)
            for (step in 2L..7L) {
                context.advance(Step(step, 1.0, "sim-tick"))
                val worker = context.observe().construction!!.workers.single { it.unitId == "worker" }
                val searches = context.drainPathDiagnostics(context.world).filter { it.unitId == "worker" }
                val view = context.observePlayer(context.world, "p1").units.single { it.unitId == "worker" }
                println("BUILD-NULL step=$step worker=$worker searches=$searches tasks=${view.tasks}")
                assertTrue(searches.isNotEmpty())
                assertTrue(searches.all { it.outcome == com.rustedwarfare.reforge.core.path.PathSearchOutcome.UNREACHABLE && it.pathPointCount == 0 })
                assertEquals(1.toByte(), worker.qBeforeDispatch, "An unavailable route must not cancel the logical request")
                assertEquals(1.toByte(), worker.singleNodePathCount)
                assertTrue(worker.pendingPathNodeCounts.isEmpty() && worker.consumedPathNodeCounts.isEmpty())
                assertEquals(710f, worker.logicalRequestX); assertEquals(110f, worker.logicalRequestY)
                assertTrue(worker.movementRequested)
                assertEquals(listOf("head", "tail"), view.tasks.map { it.actionId })
                assertNull(view.moveTarget); assertTrue(view.pathPoints.isEmpty())
            }
        } finally { context.close() }
    }

    @Test fun a_real_multiple_node_build_replan_keeps_the_prior_single_node_count() {
        val scene = openScene().copy(units = listOf(
            UnitSpawn("worker", "p1", "builder", Position(110.0, 110.0), 90.0),
            UnitSpawn("blocker", "p1", "builder", Position(310.0, 190.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "blocker" to 2L), 3L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.enablePathDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("head", "p1", "worker", "landFactory", Position(710.0, 110.0), "a".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("block", "p1", "blocker", "landFactory", Position(310.0, 110.0), "b".repeat(32), context.world, atStep = 1)))
            context.advance(Step(0, 1.0, "sim-tick"))
            val first = context.drainPathDiagnostics(context.world).single { it.unitId == "worker" }
            assertEquals(1, first.pathPointCount)
            assertEquals(1.toByte(), context.observe().construction!!.workers.single { it.unitId == "worker" }.singleNodePathCount)
            context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(1, context.observe().construction!!.buildings.size)
            assertTrue(context.drainPathDiagnostics(context.world).none { it.unitId == "worker" })
            context.advance(Step(2, 1.0, "sim-tick"))
            val worker = context.observe().construction!!.workers.single { it.unitId == "worker" }
            val searches = context.drainPathDiagnostics(context.world).filter { it.unitId == "worker" }
            val view = context.observePlayer(context.world, "p1").units.single { it.unitId == "worker" }
            println("BUILD-MULTI worker=$worker searches=$searches position=${view.position} path=${view.pathPoints}")
            assertEquals(1, searches.size)
            assertEquals(com.rustedwarfare.reforge.core.path.PathSearchReason.KNOWLEDGE_REPLAN, searches.single().reason)
            assertEquals(com.rustedwarfare.reforge.core.path.PathSearchOutcome.FOUND, searches.single().outcome)
            assertTrue(searches.single().pathPointCount > 1)
            assertEquals(listOf(searches.single().pathPointCount), worker.consumedPathNodeCounts)
            assertEquals(1.toByte(), worker.qBeforeDispatch)
            assertEquals(1.toByte(), worker.singleNodePathCount)
            assertEquals(710f, worker.logicalRequestX); assertEquals(110f, worker.logicalRequestY)
            assertTrue(worker.movementRequested)
            assertEquals(listOf("head"), view.tasks.map { it.actionId })
        } finally { context.close() }
    }

    @Test fun the_fourth_real_build_route_is_counted_after_dispatch_and_the_tail_starts_two_updates_later() {
        val scene = openScene().let { base ->
            val spawns = listOf(UnitSpawn("f", "p1", "landFactory", Position(110.0, 290.0), 0.0)) +
                listOf(310.0, 430.0, 550.0).mapIndexed { index, x -> UnitSpawn("b${index + 1}", "p1", "builder", Position(x, 370.0), 0.0) }
            base.copy(units = spawns, economyContract = EconomyContract(mapOf("p1" to 5000L, "p2" to 0L)),
                combatContract = CombatContract(1, -1, spawns.mapIndexed { index, spawn -> spawn.unitId to index + 1L }.toMap(), 5L))
        }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var step = 0L
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("produce", "p1", "f", "builder", "a".repeat(32), context.world)))
            var produced: String? = null
            while (step < 5000 && produced == null) {
                context.advance(Step(step++, 1.0, "sim-tick"))
                produced = context.observe().events.singleOrNull { it.kind == "unit-produced" }?.unitId
            }
            val workerId = assertNotNull(produced, "A real paid factory must produce the worker")
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-birth", "p1", workerId, context.world)))
            repeat(30) { context.advance(Step(step++, 1.0, "sim-tick")) }
            val born = context.observe().production!!.units.single { it.unitId == workerId }
            assertEquals(0f, born.birthRemaining)
            assertEquals("f", born.producerId)
            val idle = context.observePlayer(context.world, "p1").units.single { it.unitId == workerId }
            assertEquals(Position(110.0, 295.0), idle.position)
            assertEquals(0.0, idle.speed)
            assertTrue(idle.tasks.isEmpty())
            assertNull(idle.moveTarget)
            assertEquals(4500.0, context.observePlayer(context.world, "p1").credits)
            context.enablePathDiagnostics(context.world)
            val target = Position(710.0, 290.0)
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("head", "p1", workerId, "landFactory", target, "b".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("tail", "p1", workerId, "landFactory", Position(710.0, 430.0), "c".repeat(32), context.world, append = true)))
            for ((index, x) in listOf(310.0, 430.0, 550.0).withIndex())
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("block-${index + 1}", "p1", "b${index + 1}", "landFactory", Position(x, 290.0),
                    (index + 1).toString().repeat(32), context.world, atStep = step + index)))
            repeat(4) { index ->
                val at = step++
                context.advance(Step(at, 1.0, "sim-tick"))
                val host = context.observe()
                val worker = host.construction!!.workers.single { it.unitId == workerId }
                val view = context.observePlayer(context.world, "p1").units.single { it.unitId == workerId }
                val searches = context.drainPathDiagnostics(context.world).filter { it.unitId == workerId }
                println("BUILD-Q at=$at index=$index worker=$worker position=${view.position} route=${view.pathPoints} searches=$searches buildings=${host.construction!!.buildings}")
                assertEquals(1, searches.size, "Exactly one real search must supply this acceptance")
                assertEquals(if (index == 0) com.rustedwarfare.reforge.core.path.PathSearchReason.CONSTRUCTION_APPROACH else
                    com.rustedwarfare.reforge.core.path.PathSearchReason.KNOWLEDGE_REPLAN, searches.single().reason)
                assertEquals(1, searches.single().pathPointCount)
                assertEquals(com.rustedwarfare.reforge.core.path.PathSearchOutcome.FOUND, searches.single().outcome)
                assertEquals(at, searches.single().executionStep)
                assertEquals(index.toByte(), worker.qBeforeDispatch)
                assertEquals((index + 1).toByte(), worker.singleNodePathCount)
                assertEquals(listOf(1), worker.consumedPathNodeCounts)
                assertEquals(710f, worker.logicalRequestX); assertEquals(290f, worker.logicalRequestY)
                assertTrue(worker.movementRequested)
                assertEquals(listOf("head", "tail"), view.tasks.map { it.actionId })
                assertEquals(target, view.moveTarget)
                assertTrue(view.position.x < 140.0 && view.position.y in 260.0..319.0, "The real worker remains in its factory cells")
                assertEquals(minOf(index + 1, 3), host.construction!!.buildings.size)
            }
            context.advance(Step(step++, 1.0, "sim-tick"))
            val popped = context.observePlayer(context.world, "p1").units.single { it.unitId == workerId }
            assertEquals(listOf("tail"), popped.tasks.map { it.actionId })
            assertNull(popped.moveTarget, "The newly exposed tail cannot dispatch in the q-pop update")
            assertEquals(4.toByte(), context.observe().construction!!.workers.single { it.unitId == workerId }.qBeforeDispatch)
            assertEquals(0.toByte(), context.observe().construction!!.workers.single { it.unitId == workerId }.singleNodePathCount)
            assertTrue(context.drainPathDiagnostics(context.world).none { it.unitId == workerId })
            context.advance(Step(step, 1.0, "sim-tick"))
            val tail = context.observePlayer(context.world, "p1").units.single { it.unitId == workerId }
            assertEquals(listOf("tail"), tail.tasks.map { it.actionId })
            assertEquals(Position(710.0, 430.0), tail.moveTarget)
            assertEquals(2400.0, context.observePlayer(context.world, "p1").credits)
        } finally { context.close() }
    }

    @Test fun real_same_float_move_acceptances_keep_every_pending_entry_and_wrap_a_signed_byte() {
        for (count in listOf(127, 128, 256)) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(openScene())).context
            try {
                context.enablePathDiagnostics(context.world)
                repeat(count) { index ->
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("same-$index", "p1", "worker",
                        Position(650.0 + if (index % 2 == 0) 0.000001 else 0.0, 170.0), context.world)))
                }
                val searches = context.drainPathDiagnostics(context.world)
                assertEquals(count, searches.size)
                assertTrue(searches.all { it.outcome == com.rustedwarfare.reforge.core.path.PathSearchOutcome.FOUND && it.pathPointCount == 1 })
                val pending = context.observe().construction!!.workers.single()
                assertEquals(List(count) { 1 }, pending.pendingPathNodeCounts)
                assertEquals(0.toByte(), pending.singleNodePathCount)
                context.advance(Step(0, 1.0, "sim-tick"))
                val consumed = context.observe().construction!!.workers.single()
                assertEquals(when (count) { 127 -> 127.toByte(); 128 -> (-128).toByte(); else -> 0.toByte() }, consumed.singleNodePathCount)
                assertEquals(List(count) { 1 }, consumed.consumedPathNodeCounts)
                assertTrue(consumed.pendingPathNodeCounts.isEmpty())
                assertEquals(List(count) { 1 }, pending.pendingPathNodeCounts, "A later tail must not mutate an old host observation")
                if (count == 127) {
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("next-acceptance", "p1", "worker", Position(650.0, 170.0), context.world)))
                    context.advance(Step(1, 1.0, "sim-tick"))
                    assertEquals((-128).toByte(), context.observe().construction!!.workers.single().singleNodePathCount)
                }
            } finally { context.close() }
        }
    }

    @Test fun a_real_single_node_move_is_consumed_once_at_the_worker_tail() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(openScene())).context
        try {
            context.enablePathDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("one-node", "p1", "worker", Position(650.0, 170.0), context.world)))
            val search = context.drainPathDiagnostics(context.world).single()
            assertEquals(com.rustedwarfare.reforge.core.path.PathSearchOutcome.FOUND, search.outcome)
            assertEquals(1, search.pathPointCount, "The actual path policy must really return a single node")
            assertEquals(0.toByte(), context.observe().construction!!.workers.single().singleNodePathCount)
            context.advance(Step(0, 1.0, "sim-tick"))
            val first = context.observe().construction!!.workers.single()
            assertEquals(1.toByte(), first.singleNodePathCount)
            assertEquals(0.toByte(), first.qBeforeDispatch)
            assertEquals(listOf(1), first.consumedPathNodeCounts)
            assertTrue(first.pendingPathNodeCounts.isEmpty())
            assertEquals(650f, first.logicalRequestX)
            assertEquals(170f, first.logicalRequestY)
            assertTrue(first.movementRequested)
            assertFailsWith<UnsupportedOperationException> { (first.consumedPathNodeCounts as MutableList<Int>).add(7) }
            context.advance(Step(1, 1.0, "sim-tick"))
            val next = context.observe().construction!!.workers.single()
            assertEquals(1.toByte(), next.singleNodePathCount, "Keeping a route must not count another acceptance")
            assertTrue(next.consumedPathNodeCounts.isEmpty())
            assertEquals(listOf(1), first.consumedPathNodeCounts, "Old host observations stay frozen")
            assertTrue(context.observe().fields().toString().contains("singleNodePathCount"))
            val playerFields = context.observePlayer(context.world, "p1").fields()
            assertTrue(playerFields.containsKey("construction"))
            assertNull(playerFields["construction"])
            assertFalse(playerFields.toString().contains("singleNodePathCount"))
        } finally { context.close() }
    }

    @Test fun a_real_unreachable_build_keeps_its_head_and_tail_without_inventing_motion() {
        val base = ControlledScenario.load(inputRoot())
        val tileset = base.map.tileSets.first { 2 in it.blockedTileIds }
        assertFalse(0 in tileset.blockedTileIds || 0 in tileset.resourcePoolTileIds)
        val width = 40; val height = 25
        val blocked = BooleanArray(width * height) { it % width == 20 }
        val gids = IntArray(width * height) { if (blocked[it]) tileset.firstGid + 2 else tileset.firstGid }
        val map = MapDefinition(width, height, 20, 20, base.map.tileSets, gids, BlockingGrid(width, height, blocked))
        for (row in 0 until height) for (column in 0 until width) {
            assertEquals(column == 20, map.blocking.isBlocked(column, row))
            assertEquals(column == 20, map.groundGids[row * width + column] - tileset.firstGid in tileset.blockedTileIds)
        }
        val origin = Position(120.0, 170.0)
        val target = Position(650.0, 170.0)
        val scene = base.copy(map = map,
            units = listOf(UnitSpawn("worker", "p1", "builder", origin, 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        val initialized = SimulationContext.initialize(scene)
        val context = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
        try {
            val move = assertIs<ActionResult.Accepted>(context.submit(MoveAction("wall-control", "p1", "worker", target, context.world)))
            assertTrue(move.events.any { it.kind == "path-unreachable" }, "The complete real wall must block the actual path policy")
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("far-build", "p1", "worker", "landFactory",
                target, "1".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(PatrolAction("tail", "p1", "worker", Position(170.0, 290.0), true, context.world)))
            repeat(6) { index ->
                context.advance(Step(index.toLong(), 1.0, "sim-tick"))
                val owner = context.observePlayer(context.world, "p1")
                val worker = owner.units.single()
                assertEquals(listOf("far-build", "tail"), worker.tasks.map { it.actionId })
                assertEquals(listOf(UnitTaskKind.BUILD, UnitTaskKind.PATROL), worker.tasks.map { it.kind })
                assertEquals(origin, worker.position)
                assertNull(worker.moveTarget)
                assertTrue(worker.pathPoints.isEmpty())
                assertEquals(1750.0, owner.credits)
                assertTrue(owner.events.none { it.kind == "construction-created" || it.kind == "construction-completed" })
            }
        } finally { context.close() }
    }

    private fun openScene(): Scenario {
        val base = ControlledScenario.load(inputRoot())
        val tileset = base.map.tileSets.first { 2 in it.blockedTileIds }
        val width = 40; val height = 25
        return base.copy(map = MapDefinition(width, height, 20, 20, base.map.tileSets,
            IntArray(width * height) { tileset.firstGid }, BlockingGrid(width, height, BooleanArray(width * height))),
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
    }

    private fun inputRoot(): Path = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/queue") } ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input"))
}
