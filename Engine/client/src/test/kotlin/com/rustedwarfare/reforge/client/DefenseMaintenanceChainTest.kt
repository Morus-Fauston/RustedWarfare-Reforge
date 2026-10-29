package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DefenseMaintenanceChainTest {
    @Test fun two_workers_share_one_turret_then_queue_resume_repair_and_reclaim_the_real_defense_buildings() {
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context }
        val c = contexts[1]
        var step = 0L
        try {
            for (context in contexts) {
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("turret-a", "p1", "a", "c_turret_t1",
                    Position(190.0, 170.0), "1".repeat(32), context.world)))
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("shield-tail", "p1", "a", "laserDefence",
                    Position(270.0, 170.0), "3".repeat(32), context.world, append = true)))
            }
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("turret-b", "p1", "b", "c_turret_t1",
                Position(190.0, 170.0), "2".repeat(32), c.world)))
            repeat(101) {
                contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                step++
            }
            val turret = c.observe().units.single { it.typeId == "c_turret_t1" }
            assertTrue(turret.constructionProgress > contexts[0].observe().units.single { it.typeId == "c_turret_t1" }.constructionProgress)
            assertEquals(1250.0, c.observePlayer(c.world, "p1").credits, "Two workers pay for one site exactly once")
            assertTrue(c.observe().units.filter { it.typeId == "builder" }.all { it.constructionTargetId == turret.unitId })
            assertEquals(listOf(UnitTaskKind.CONSTRUCT, UnitTaskKind.BUILD), c.observe().units.single { it.unitId == "a" }.tasks.map { it.kind })
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            while (c.observe().units.none { it.typeId == "laserDefence" } && step < 2000L) tick()
            assertEquals(1f, c.observe().units.single { it.unitId == turret.unitId }.constructionProgress)
            val shield = c.observe().units.single { it.typeId == "laserDefence" }
            assertEquals(0f, shield.constructionProgress)
            assertEquals(50.0, c.observePlayer(c.world, "p1").credits)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("pause-shield", "p1", "a", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(ResumeConstructionAction("resume-shield", "p1", "b", shield.unitId, c.world)))
            while (c.observe().units.single { it.unitId == shield.unitId }.constructionProgress < 1f && step < 4000L) tick()
            assertEquals(1f, c.observe().units.single { it.unitId == shield.unitId }.constructionProgress)
            assertEquals(50.0, c.observePlayer(c.world, "p1").credits, "Resuming the existing shield is free")
            assertIs<ActionResult.Accepted>(c.submit(StopAction("stop-b", "p1", "b", c.world)))

            assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("weaken-turret", "p1", "a", turret.unitId, c.world)))
            while (c.observe().units.single { it.unitId == turret.unitId }.health!! > 689.8 && step < 5000L) tick()
            val damaged = c.observe().units.single { it.unitId == turret.unitId }.health!!
            assertTrue(damaged in 0.0..689.8)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("stop-weaken", "p1", "a", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(RepairBuildingAction("repair-turret", "p1", "a", turret.unitId, c.world)))
            while (c.observe().units.single { it.unitId == turret.unitId }.health!! < 700.0 && step < 6000L) tick()
            assertEquals(700.0, c.observe().units.single { it.unitId == turret.unitId }.health)
            assertEquals(50.0, c.observePlayer(c.world, "p1").credits)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("stop-a", "p1", "a", c.world)))

            assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("reclaim-shield", "p1", "b", shield.unitId, c.world)))
            var reclaimEvents = 0
            while (c.observe().units.any { it.unitId == shield.unitId } && step < 7500L) {
                reclaimEvents += tick().events.count { it.kind == "building-reclaimed" && it.unitId == shield.unitId }
            }
            assertTrue(c.observe().units.none { it.unitId == shield.unitId })
            assertEquals(1, reclaimEvents)
            assertEquals(1010.0000143051147, c.observePlayer(c.world, "p1").credits)
            assertEquals("invalid-maintenance-target", assertIs<ActionResult.Rejected>(c.submit(
                ReclaimBuildingAction("no-second-refund", "p1", "a", shield.unitId, c.world))).error.code)
            repeat(30) { tick() }
            assertEquals(1010.0000143051147, c.observePlayer(c.world, "p1").credits)
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("reuse-released-footprint", "p1", "a", "c_turret_t1",
                Position(270.0, 170.0), "4".repeat(32), c.world)))
            while (c.observe().units.count { it.typeId == "c_turret_t1" } < 2 && step < 8200L) tick()
            assertEquals(setOf(Position(180.0, 160.0), Position(260.0, 160.0)),
                c.observe().units.filter { it.typeId == "c_turret_t1" }.map { it.position }.toSet())
            assertEquals(510.00001430511475, c.observePlayer(c.world, "p1").credits)
        } finally { contexts.forEach { it.close() } }
    }

    private fun scene(): Scenario {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input")
        val base = ControlledScenario.load(root)
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        return base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground },
            BlockingGrid(40, 25, BooleanArray(1000))),
            units = listOf(UnitSpawn("a", "p1", "builder", Position(120.0, 170.0), 90.0),
                UnitSpawn("b", "p1", "builder", Position(120.0, 210.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("a" to 1L, "b" to 2L), 3L))
    }
}
