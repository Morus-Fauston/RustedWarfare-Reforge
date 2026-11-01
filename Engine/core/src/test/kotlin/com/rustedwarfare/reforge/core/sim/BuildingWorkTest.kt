package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class BuildingWorkTest {
    @Test fun host_preserves_the_first_active_preparation_decay_before_the_complete_work_period() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "landFactory",
                Position(190.0, 170.0), "a".repeat(32), context.world)))
            context.advance(Step(0L, 1.0, "sim-tick"))
            val firstActive = context.advance(Step(1L, 1.0, "sim-tick"))
            assertTrue(firstActive.construction!!.workers.single().working)
            assertEquals(0f, firstActive.construction.workers.single().preparation)
            var step = 2L
            while (context.observe().units.single { it.typeId == "landFactory" }.constructionProgress < 1f && step < 1100L)
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(1033L, step)
            assertEquals(1050L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
    }

    @Test fun paid_creation_and_preparation_reach_one_completion_without_rebuilding_the_worker_or_queue() {
        val scene = scene()
        val worker = worker(scene)
        val units = linkedMapOf(worker.unitId to worker)
        val policies = ExperimentalPolicies()
        var objectId = 11L
        var birth = 0L
        val access = object : BuildingWorkAccess {
            override fun path(worker: MutableUnit, target: Position, range: Double, task: UnitTask, step: Long): BuildingRoute? =
                policies.path.findPath(com.rustedwarfare.reforge.core.path.PlanningTerrain.fromNoFog(scene.map),
                    worker.position, target, worker.definition.radius, targetRange = range)?.let { BuildingRoute(it, -1L, -1L) }
            override fun birth(): BuildingBirth = BuildingBirth(objectId++, "~produced/" + (++birth).toString().padStart(20, '0'))
        }
        var work = BuildingWork.initial()
        var ledger = ProductionLedger.initial(mapOf("p1" to 1750L), emptyMap())
        val initial = work
        val initialLedger = ledger
        val order = BuildAction("build", "p1", "worker", "landFactory", Position(190.0, 170.0),
            "a".repeat(32), WorldHandle("test"))
        assertNull(work.request(order, worker, scene))
        var step = 0L
        val events = mutableListOf<Event>()
        fun tick() {
            val wasWorking = worker.constructionWorking
            worker.constructionWorking = false
            work = work.advanceClock(1.0)
            val frame = BuildingWorkFrame(scene, units, Step(step++, 1.0, "sim-tick"), null,
                policies.coordinates, policies.steering, access)
            val change = work.dispatch(worker, frame, wasWorking, ledger, null)
            work = change.work; ledger = change.ledger
            change.created?.let { units[it.unitId] = it }
            events += change.events
            BuildingWork.finishActivity(worker, units, 1.0)
        }
        tick()
        val created = units.getValue("constructed:p1:" + "a".repeat(32))
        assertEquals(1050L, ledger.balances.getValue("p1"))
        assertEquals(1750L, initialLedger.balances.getValue("p1"))
        assertEquals(Position(190.0, 170.0), created.position)
        assertEquals(11L, created.objectId)
        assertEquals("~produced/00000000000000000001", created.updateKey)
        assertEquals(0f, created.constructionProgress)
        assertFalse(BuildingWork.isReady(created))
        assertTrue(ledger.isActive(created.unitId))
        assertEquals(listOf(Event("construction-created", created.unitId)), events)
        assertEquals(setOf("a".repeat(32)), work.nonces.getValue("p1"))
        assertTrue(initial.nonces.isEmpty())
        assertEquals("duplicate-building-nonce", work.request(order, worker, scene)?.code)
        while (!BuildingWork.isReady(created) && step < 1100L) tick()
        assertEquals(1033L, step) // creation at0; first active update decays, then30 preparation and1001 work updates.
        assertTrue(BuildingWork.isReady(created))
        assertEquals(1, events.count { it.kind == "construction-completed" })
        tick()
        assertTrue(worker.tasks.isEmpty())
        assertEquals(1050L, ledger.balances.getValue("p1"))
        assertEquals(1, events.count { it.kind == "construction-completed" })
    }

    @Test fun safe_request_keeps_payment_deferred_and_clock_uses_the_existing_float_tick() {
        val scene = scene()
        val worker = worker(scene)
        val work = BuildingWork.initial()
        val order = BuildAction("build", "p1", "worker", "landFactory", Position(190.0, 170.0),
            "a".repeat(32), WorldHandle("test"))
        assertNull(BuildingWork.validateEnvelope(order))
        assertNull(work.request(order, worker, scene))
        assertEquals(listOf(UnitTask(UnitTaskKind.BUILD, "build", Position(190.0, 170.0),
            buildingTypeId = "landFactory", buildingNonce = "a".repeat(32))), worker.tasks)
        assertEquals(0, work.clock)
        assertTrue(work.nonces.isEmpty())
        val later = work.advanceClock(1.0).advanceClock(1.0)
        assertEquals(32, later.clock)
        assertEquals(0, work.clock)
        assertTrue(later.nonces.isEmpty())
        assertEquals("unsupported-construction-type", work.request(order.copy(buildingTypeId = "missing"),
            worker, scene)?.code)
        assertEquals("invalid-building-nonce", BuildingWork.validateEnvelope(order.copy(buildingNonce = "X"))?.code)
        assertEquals("invalid-target", BuildingWork.validateEnvelope(order.copy(target = Position(Double.NaN, 0.0)))?.code)
    }

    private fun scene(): Scenario {
        val root = System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/factory/units") }
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isDirectory(it.resolve("SimulationTest/public")) }
                .resolve("SimulationTest/public/inputs/build-economy/factory/units")
        val builder = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadBuilder(root.resolve("builder.properties"))).value
        val factory = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadFactory(root.resolve("landFactory.properties"))).value
        val base = BuildingTestContent.scenario()
        return base.copy(unitDefinitions = base.unitDefinitions + mapOf(builder.id to builder, factory.id to factory),
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 1L), 2L))
    }
    private fun worker(scene: Scenario) = MutableUnit("worker", "p1", "builder", scene.unitDefinitions.getValue("builder"),
        Position(120.0, 170.0), 90.0)
}
