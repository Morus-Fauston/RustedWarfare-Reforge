package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DefenseCompletionOrderingTest {
    @Test fun an_earlier_worker_enables_the_turrets_slot_on_completion_but_a_later_born_worker_does_not_replay_that_slot() {
        for (laterWorker in listOf(false, true)) {
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
            try {
                var step = 0L
                fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("build", "p1", "worker", "c_turret_t1",
                    Position(190.0, 170.0), "1".repeat(32), c.world)))
                val turret = tick().units.single { it.typeId == "c_turret_t1" }.unitId
                assertEquals(0f, c.observe().units.single { it.unitId == turret }.constructionProgress)
                assertIs<ActionResult.Accepted>(c.submit(StopAction("pause", "p1", "worker", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("waiting-gun", "p1", turret, "upgrade_gun", c.world)))
                var worker = "worker"
                if (laterWorker) {
                    assertIs<ActionResult.Accepted>(c.submit(ProduceAction("later-builder", "p1", "factory", "builder", "2".repeat(32), c.world)))
                    while (c.observe().production!!.units.isEmpty() && step < 1000L) tick()
                    val born = c.observe().production!!.units.single()
                    worker = born.unitId
                    assertTrue(c.observe().construction!!.buildings.single().updateKey < born.updateKey)
                    assertIs<ActionResult.Accepted>(c.submit(MoveAction("approach", "p1", worker, Position(120.0, 170.0), c.world)))
                    while (c.observe().units.single { it.unitId == worker }.moveTarget != null && step < 2500L) tick()
                    assertNull(c.observe().units.single { it.unitId == worker }.moveTarget)
                }
                assertEquals(0f, c.observePlayer(c.world, "p1").upgradeQueues.single { it.unitId == turret }.items.single().progress)
                assertIs<ActionResult.Accepted>(c.submit(ResumeConstructionAction("finish", "p1", worker, turret, c.world)))
                var completed: Observation? = null
                while (completed == null && step < 5000L) {
                    val view = tick()
                    if (view.events.any { it.kind == "construction-completed" && it.unitId == turret }) completed = view
                    else assertEquals(0f, c.observePlayer(c.world, "p1").upgradeQueues.single { it.unitId == turret }.items.single().progress)
                }
                val finish = assertNotNull(completed)
                assertEquals(1f, finish.units.single { it.unitId == turret }.constructionProgress)
                assertEquals(1, finish.events.count { it.kind == "construction-completed" && it.unitId == turret })
                assertEquals(if (laterWorker) 0f else .001f,
                    c.observePlayer(c.world, "p1").upgradeQueues.single { it.unitId == turret }.items.single().progress)
                assertEquals(if (laterWorker) 2000.0 else 2500.0, c.observePlayer(c.world, "p1").credits)
                val next = tick()
                assertTrue(next.events.none { it.kind == "construction-completed" && it.unitId == turret })
                assertEquals(if (laterWorker) .001f else .002f,
                    c.observePlayer(c.world, "p1").upgradeQueues.single { it.unitId == turret }.items.single().progress)
            } finally { c.close() }
        }
    }

    private fun scene(): Scenario {
        val root = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/construction") }
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input"))
        val base = ControlledScenario.load(root)
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        val units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(650.0, 160.0), 0.0))
        return base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground },
            BlockingGrid(40, 25, BooleanArray(1000))), units = units,
            economyContract = EconomyContract(mapOf("p1" to 4000L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, units.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), 3L))
    }
}
