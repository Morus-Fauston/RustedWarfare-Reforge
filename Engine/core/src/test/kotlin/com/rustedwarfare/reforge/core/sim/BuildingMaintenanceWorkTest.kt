package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class BuildingMaintenanceWorkTest {
    @Test fun paid_pending_production_is_refunded_once_with_the_site_and_the_worker_tail_left_unexecuted() {
        val scene = scene()
        val worker = worker(scene)
        val helper = worker(scene).copy(unitId = "helper")
        val factory = factory(scene).also { it.health = 1.0 }
        val units = linkedMapOf(worker.unitId to worker, helper.unitId to helper, factory.unitId to factory)
        val initial = ProductionLedger.initial(mapOf("p1" to 1750L), mapOf("factory" to
            FactoryProductionSpec("p1", mapOf("builder" to checkNotNull(scene.unitDefinitions.getValue("builder").build)))),
            incomeEnabled = true)
        val pending = assertIs<ProductionChange.Accepted>(initial.enqueue("factory", "builder", "1".repeat(32))).ledger
        var ledger = pending
        val work = BuildingWork.initial()
        val world = WorldHandle("module")
        assertNull(work.requestTarget(ReclaimUnitAction("reclaim", "p1", "worker", "factory", world),
            worker, scene, units))
        assertNull(work.requestTarget(ReclaimUnitAction("also", "p1", "helper", "factory", world),
            helper, scene, units))
        worker.taskQueue = worker.taskQueue.append(UnitTask(UnitTaskKind.PATROL, "tail", Position(450.0, 170.0)))
        val policies = ExperimentalPolicies()
        val access = object : BuildingWorkAccess {
            override fun path(worker: MutableUnit, target: Position, range: Double, task: UnitTask, step: Long): BuildingRoute? =
                policies.path.findPath(com.rustedwarfare.reforge.core.path.PlanningTerrain.fromNoFog(scene.map),
                    worker.position, target, worker.definition.radius, targetRange = range)?.let { BuildingRoute(it, -1L, -1L) }
            override fun construct(ownerPlayerId: String, typeId: String, position: Position, nonce: String,
                                   paidLedger: ProductionLedger): ConstructionRegistration =
                error("Maintenance does not create an object")
        }
        fun frame(step: Long) = BuildingWorkFrame(scene, units, Step(step, 1.0, "sim-tick"), null,
            policies.coordinates, policies.steering, access)
        repeat(99) { step ->
            val change = work.advanceActivity(worker, frame(step.toLong()), ledger, null)
            ledger = change.ledger
            assertNull(change.reclaimedUnitId)
            assertTrue(factory.alive)
            assertTrue(change.events.isEmpty())
        }
        val terminal = work.advanceActivity(worker, frame(99L), ledger, null)
        assertEquals("factory", terminal.reclaimedUnitId)
        assertEquals(2310.0000083446503, terminal.ledger.observe().preciseBalances!!.getValue("p1"))
        assertEquals(1250.0, pending.observe().preciseBalances!!.getValue("p1"))
        assertEquals(1750.0, initial.observe().preciseBalances!!.getValue("p1"))
        assertTrue(terminal.ledger.queue("factory").isEmpty())
        assertEquals(1, pending.queue("factory").size)
        assertFalse(terminal.ledger.isActive("factory"))
        assertTrue(factory.reclaimed)
        assertFalse(factory.alive)
        assertEquals(listOf(Event("building-reclaimed", "factory"), Event("maintenance-completed", "worker")), terminal.events)
        assertEquals(listOf(UnitTask(UnitTaskKind.PATROL, "tail", Position(450.0, 170.0))), worker.tasks)
        assertEquals(Position(120.0, 170.0), worker.position)
        assertFalse(worker.constructionWorking)
        val second = work.advanceActivity(helper, frame(99L), terminal.ledger, null)
        assertSame(terminal.ledger, second.ledger)
        assertNull(second.reclaimedUnitId)
        assertTrue(second.events.isEmpty())
        assertTrue(helper.tasks.isEmpty())
    }

    @Test fun maintenance_and_resume_share_work_admission_without_opening_the_new_build_menu() {
        val scene = scene()
        val worker = worker(scene)
        val factory = factory(scene)
        val units = linkedMapOf(worker.unitId to worker, factory.unitId to factory)
        val work = BuildingWork.initial()
        val world = WorldHandle("module")
        assertEquals("invalid-construction-target", work.requestTarget(ResumeConstructionAction("full", "p1",
            "worker", "factory", world), worker, scene, units)?.code)
        assertEquals("invalid-maintenance-target", work.requestTarget(RepairUnitAction("missing", "p1",
            "worker", "missing", world), worker, scene, units)?.code)
        assertEquals("cannot-maintain", work.requestTarget(ReclaimUnitAction("disabled", "p1", "worker",
            "factory", world), worker, scene.copy(maintenanceContract = null), units)?.code)
        assertNull(work.requestTarget(RepairUnitAction("repair", "p1", "worker", "factory", world),
            worker, scene, units))
        assertNull(work.requestTarget(ReclaimUnitAction("reclaim", "p1", "worker", "factory", world,
            append = true), worker, scene, units))
        assertEquals(listOf(UnitTask(UnitTaskKind.CONSTRUCT, "repair", targetUnitId = "factory"),
            UnitTask(UnitTaskKind.RECLAIM, "reclaim", targetUnitId = "factory")), worker.tasks)
        factory.constructionProgress = .25f
        assertNull(work.requestTarget(ResumeConstructionAction("resume", "p1", "worker", "factory", world),
            worker, scene, units))
        assertEquals(listOf(UnitTask(UnitTaskKind.CONSTRUCT, "resume", targetUnitId = "factory")), worker.tasks)
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
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
                UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)),
            maintenanceContract = MaintenanceContract(mapOf("landFactory" to 700L)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 1L, "factory" to 2L), 3L))
    }
    private fun worker(scene: Scenario) = MutableUnit("worker", "p1", "builder", scene.unitDefinitions.getValue("builder"),
        Position(120.0, 170.0), 90.0)
    private fun factory(scene: Scenario) = MutableUnit("factory", "p1", "landFactory", scene.unitDefinitions.getValue("landFactory"),
        Position(190.0, 170.0), 0.0)
}
