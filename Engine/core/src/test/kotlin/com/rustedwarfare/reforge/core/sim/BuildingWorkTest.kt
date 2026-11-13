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
        val registration = StepState(units = units, nextObjectId = 11L)
        val access = object : BuildingWorkAccess {
            override fun path(worker: MutableUnit, target: Position, range: Double, task: UnitTask, step: Long): BuildingRoute? =
                policies.path.findPath(com.rustedwarfare.reforge.core.path.PlanningTerrain.fromNoFog(scene.map),
                    worker.position, target, worker.definition.radius, targetRange = range)?.let { BuildingRoute(it, -1L, -1L) }
            override fun construct(ownerPlayerId: String, typeId: String, position: Position, nonce: String,
                                   paidLedger: ProductionLedger): ConstructionRegistration =
                UnitRegistration.construction(registration, scene, ownerPlayerId, typeId, position, nonce, paidLedger)
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

    @Test fun two_builders_in_one_step_pay_once_and_reuse_the_same_site_without_reserving_the_second_nonce() {
        val base = scene()
        val first = base.units.single()
        val input = base.copy(units = listOf(first, first.copy(unitId = "zhelper", position = Position(120.0, 210.0))),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 1L, "zhelper" to 2L), 3L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        val nonce = "a".repeat(32)
        val unused = "b".repeat(32)
        val id = "constructed:p1:$nonce"
        try {
            for ((actor, value) in listOf("worker" to nonce, "zhelper" to unused)) {
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("build-$actor", "p1", actor,
                    "landFactory", Position(190.0, 170.0), value, context.world)))
            }
            assertEquals(1750L, context.observePlayer(context.world, "p1").balance)
            val created = context.advance(Step(0L, 1.0, "sim-tick"))
            assertEquals(listOf(id), created.units.filter { it.unitId.startsWith("constructed:") }.map { it.unitId })
            assertEquals(0f, created.units.single { it.unitId == id }.constructionProgress)
            assertEquals(1050L, context.observePlayer(context.world, "p1").balance)
            assertEquals(listOf(Event("construction-created", id)), created.events.filter { it.kind == "construction-created" })
            assertEquals(setOf(nonce), created.construction!!.nonces.getValue("p1"))
            assertEquals(1L, created.production!!.lastBirthSerial)
            assertEquals(4L, created.combat!!.nextObjectId)
            for (actor in listOf("worker", "zhelper")) {
                val task = created.units.single { it.unitId == actor }.tasks.single()
                assertEquals(UnitTaskKind.CONSTRUCT, task.kind)
                assertEquals(id, task.targetUnitId)
            }
            for (step in 1L..60L) context.advance(Step(step, 1.0, "sim-tick"))
            val continued = context.observe()
            assertTrue(continued.units.single { it.unitId == id }.constructionProgress > 0f)
            assertEquals(1050L, context.observePlayer(context.world, "p1").balance)
            assertEquals(setOf(nonce), continued.construction!!.nonces.getValue("p1"))
            assertEquals(1, continued.construction.buildings.size)
            assertEquals(1L, continued.production!!.lastBirthSerial)
        } finally { context.close() }
    }

    @Test fun real_enemy_shot_overflow_after_site_creation_rolls_back_payment_footprint_nonce_and_identity_before_same_step_retry() {
        val base = scene()
        val input = base.copy(units = base.units + UnitSpawn("z", "p2", "c_tank", Position(310.0, 170.0), 270.0),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 1L, "z" to 2L), Long.MAX_VALUE - 1))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
        val nonce = "a".repeat(32)
        val id = "constructed:p1:$nonce"
        try {
            for (context in contexts) assertIs<ActionResult.Accepted>(context.submit(BuildAction("first", "p1", "worker",
                "landFactory", Position(190.0, 170.0), nonce, context.world)))
            val failed = contexts[0]
            val old = failed.observe()
            val oldPlayer = failed.observePlayer(failed.world, "p1")
            // The worker creates the real site first; the later old enemy can now shoot it.
            // The site takes MAX-1, then the real projectile allocation at MAX overflows.
            assertFailsWith<ArithmeticException> { failed.advance(Step(0L, 1.0, "sim-tick")) }
            assertSame(old, failed.observe())
            assertSame(oldPlayer, failed.observePlayer(failed.world, "p1"))
            assertTrue(failed.observe().units.none { it.unitId == id })
            assertTrue(failed.observe().construction!!.buildings.isEmpty())
            assertTrue(failed.observe().construction!!.nonces.isEmpty())
            assertEquals(1750L, failed.observePlayer(failed.world, "p1").balance)
            assertEquals(0L, failed.observe().production!!.lastBirthSerial)
            assertEquals(Long.MAX_VALUE - 1, failed.observe().combat!!.nextObjectId)
            for (context in contexts) {
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "worker", context.world)))
                // This legal overlapping footprint stays outside the enemy's actual firing range.
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("retry", "p1", "worker", "landFactory",
                    Position(170.0, 150.0), nonce, context.world)))
                context.advance(Step(0L, 1.0, "sim-tick"))
            }
            val actual = failed.observe()
            val expected = contexts[1].observe()
            assertEquals(expected.units, actual.units)
            assertEquals(expected.events, actual.events)
            assertEquals(expected.actionResults, actual.actionResults)
            assertEquals(expected.combat, actual.combat)
            assertEquals(expected.economy!!.balances, actual.economy!!.balances)
            assertEquals(expected.construction!!.clock, actual.construction!!.clock)
            assertEquals(expected.construction.buildings, actual.construction.buildings)
            assertEquals(expected.construction.workers, actual.construction.workers)
            assertEquals(expected.construction.nonces, actual.construction.nonces)
            assertEquals(1050L, failed.observePlayer(failed.world, "p1").balance)
            assertEquals(listOf(id), actual.construction.buildings.map { it.unitId })
            assertEquals(Position(170.0, 150.0), actual.units.single { it.unitId == id }.position)
            assertEquals(setOf(nonce), actual.construction.nonces.getValue("p1"))
            assertEquals(1L, actual.production!!.lastBirthSerial)
            assertEquals(Long.MAX_VALUE, actual.combat!!.nextObjectId)
            assertEquals(1, actual.events.count { it.kind == "construction-created" })
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun factory_registration_failure_preserves_the_site_identity_payment_and_world() {
        val valid = scene()
        // A direct module caller can supply an incomplete catalog; full Context rejects it at initialization.
        val input = valid.copy(unitDefinitions = valid.unitDefinitions - "c_tank")
        val worker = worker(valid)
        val units = linkedMapOf(worker.unitId to worker)
        val registration = StepState(units = units, nextObjectId = 11L)
        val policies = ExperimentalPolicies()
        val access = object : BuildingWorkAccess {
            override fun path(worker: MutableUnit, target: Position, range: Double, task: UnitTask, step: Long): BuildingRoute? =
                policies.path.findPath(com.rustedwarfare.reforge.core.path.PlanningTerrain.fromNoFog(input.map),
                    worker.position, target, worker.definition.radius, targetRange = range)?.let { BuildingRoute(it, -1L, -1L) }
            override fun construct(ownerPlayerId: String, typeId: String, position: Position, nonce: String,
                                   paidLedger: ProductionLedger): ConstructionRegistration =
                UnitRegistration.construction(registration, input, ownerPlayerId, typeId, position, nonce, paidLedger)
        }
        val work = BuildingWork.initial()
        val ledger = ProductionLedger.initial(mapOf("p1" to 1750L), emptyMap())
        val nonce = "a".repeat(32)
        assertNull(work.request(BuildAction("build", "p1", "worker", "landFactory", Position(190.0, 170.0),
            nonce, WorldHandle("direct-module")), worker, input))
        val frame = BuildingWorkFrame(input, units, Step(0L, 1.0, "sim-tick"), null,
            policies.coordinates, policies.steering, access)
        assertFailsWith<NoSuchElementException> { work.dispatch(worker, frame, false, ledger, null) }
        assertEquals(listOf("worker"), units.keys.toList())
        assertEquals(1750L, ledger.balances.getValue("p1"))
        assertTrue(work.nonces.isEmpty())
        assertEquals(UnitTaskKind.BUILD, worker.tasks.single().kind)
        assertEquals(11L, registration.nextObjectId)
        assertEquals(0L, registration.lastBirthSerial)
        assertTrue(registration.pendingEvents.isEmpty())
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
