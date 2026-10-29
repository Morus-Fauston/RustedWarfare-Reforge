package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MaintenanceOrderingTest {
    @Test fun a_factory_that_updates_before_its_reclaimer_keeps_its_completed_product_while_the_other_order_refunds_it() {
        for (workerFirst in listOf(true, false)) {
            val worker = if (workerFirst) "a-worker" else "z-worker"
            val scene = base().copy(units = listOf(UnitSpawn(worker, "p1", "builder", Position(120.0, 170.0), 90.0),
                UnitSpawn("m-target", "p1", "landFactory", Position(190.0, 170.0), 0.0)),
                combatContract = CombatContract(1, -1, mapOf(worker to 1L, "m-target" to 2L), 3L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                var step = 0L
                fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
                assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("weaken", "p1", worker, "m-target", c.world)))
                while (c.observe().units.single { it.unitId == "m-target" }.health!! > 1.02 && step < 1500) tick()
                assertTrue(c.observe().units.single { it.unitId == "m-target" }.health!! in 0.0..1.02)
                assertIs<ActionResult.Accepted>(c.submit(StopAction("pause", "p1", worker, c.world)))
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "m-target", "builder", "1".repeat(32), c.world)))
                repeat(401) { tick() }
                assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("terminal", "p1", worker, "m-target", c.world)))
                repeat(99) { tick() }
                assertEquals(99f, c.observe().construction!!.workers.single().elapsed)
                val view = tick()
                assertTrue(view.units.none { it.unitId == "m-target" })
                assertEquals(if (workerFirst) 2310.0000083446503 else 1810.0000083446503,
                    c.observePlayer(c.world, "p1").credits)
                assertEquals(if (workerFirst) 0 else 1, view.production!!.units.size)
                if (!workerFirst) {
                    val produced = view.production!!.units.single()
                    assertEquals("m-target", produced.producerId)
                    assertTrue(view.units.single { it.unitId == produced.unitId }.alive)
                    assertIs<ActionResult.Accepted>(c.submit(MoveAction("still-valid", "p1", produced.unitId, Position(310.0, 170.0), c.world)))
                    repeat(35) { tick() }
                }
            } finally { c.close() }
        }
    }

    @Test fun same_frame_upgrade_completion_changes_the_base_refund_only_if_the_building_updates_first() {
        for (workerFirst in listOf(true, false)) {
            val worker = if (workerFirst) "a-worker" else "z-worker"
            val base = base()
            val scene = base.copy(units = listOf(UnitSpawn(worker, "p1", "builder", Position(120.0, 290.0), 90.0),
                UnitSpawn("m-target", "p1", "extractorT1", Position(190.0, 290.0), 0.0)),
                economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 0L)),
                combatContract = CombatContract(1, -1, mapOf(worker to 1L, "m-target" to 2L), 3L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                var step = 0L
                fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
                assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("weaken", "p1", worker, "m-target", c.world)))
                while (c.observe().units.single { it.unitId == "m-target" }.health!! > 1.02 && step < 1200) tick()
                // This actual ordinary T1 damage history leaves less than .8 HP, so even proportional T2 conversion remains terminal.
                assertTrue(c.observe().units.single { it.unitId == "m-target" }.health!! in 0.0..<.8)
                assertIs<ActionResult.Accepted>(c.submit(StopAction("pause", "p1", worker, c.world)))
                assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("upgrade", "p1", "m-target", "upgrade_t2", c.world)))
                repeat(1567) { tick() }
                assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("terminal", "p1", worker, "m-target", c.world)))
                repeat(99) { tick() }
                assertEquals(99f, c.observe().construction!!.workers.single().elapsed)
                assertTrue(c.observe().incomeStates.single { it.unitId == "m-target" }.elapsed < 39f)
                val before = c.observePlayer(c.world, "p1").credits!!
                val view = tick()
                assertTrue(view.units.none { it.unitId == "m-target" })
                assertEquals(before + if (workerFirst) 1960.0000083446503 else 1680.0000250339508,
                    c.observePlayer(c.world, "p1").credits)
                assertEquals(if (workerFirst) 0 else 1, view.events.count { it.kind == "upgrade-completed" })
                val paid = c.observePlayer(c.world, "p1").credits
                repeat(40) { tick() }
                assertEquals(paid, c.observePlayer(c.world, "p1").credits)
            } finally { c.close() }
        }
    }

    private fun base(): Scenario = ControlledScenario.load(generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-005/20261029-implementation-01/input"))
}
