package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class UnfinishedDefenseDamageTest {
    @Test fun real_tank_hits_apply_43_75_to_either_unfinished_defense_and_death_refunds_only_its_queued_upgrade_once_and_releases_space() {
        for ((type, maximum) in listOf("c_turret_t1" to 700.0, "laserDefence" to 500.0)) {
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
            try {
                var step = 0L
                var deaths = 0
                var refunds = 0
                fun tick(): Observation {
                    val frame = c.advance(Step(step++, 1.0, "sim-tick"))
                    deaths += frame.events.count { it.kind == "unit-died" }
                    refunds += frame.events.count { it.kind == "upgrade-refunded" }
                    return frame
                }
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("build", "p1", "worker", type,
                    Position(190.0, 170.0), "1".repeat(32), c.world)))
                val site = tick().units.single { it.typeId == type }
                assertEquals(0f, site.constructionProgress)
                assertIs<ActionResult.Accepted>(c.submit(StopAction("stop-worker", "p1", "worker", c.world)))
                if (type == "c_turret_t1") assertIs<ActionResult.Accepted>(c.submit(
                    UpgradeAction("pending-upgrade", "p1", site.unitId, "upgrade_gun", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("real-enemy", "p2", "enemy", site.unitId, c.world)))
                var firstHit: Observation? = null
                while (firstHit == null && step < 200L) {
                    val frame = tick()
                    if (frame.units.single { it.unitId == site.unitId }.health!! < maximum) firstHit = frame
                }
                val hit = assertNotNull(firstHit)
                val target = hit.units.single { it.unitId == site.unitId }
                assertEquals(maximum - 43.75, target.health)
                assertEquals(0f, target.constructionProgress)
                assertEquals(43.75, hit.projectileEvents.single { it.kind == "impact" && it.projectile.targetUnitId == site.unitId }.damage)
                assertTrue(hit.events.any { it.kind == "unit-damaged" && it.unitId == site.unitId })
                while (c.observe().units.single { it.unitId == site.unitId }.alive && step < 2000L) tick()
                assertFalse(c.observe().units.single { it.unitId == site.unitId }.alive)
                assertEquals(1, deaths)
                assertEquals(if (type == "c_turret_t1") 1 else 0, refunds)
                val expectedBalance = if (type == "c_turret_t1") 1250.0 else 550.0
                assertEquals(expectedBalance, c.observePlayer(c.world, "p1").credits)
                assertTrue(c.observePlayer(c.world, "p1").upgradeQueues.all { it.items.isEmpty() })
                repeat(20) { tick() }
                assertEquals(1, deaths)
                assertEquals(if (type == "c_turret_t1") 1 else 0, refunds)
                assertEquals(expectedBalance, c.observePlayer(c.world, "p1").credits)
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("released-space", "p1", "worker", "c_turret_t1",
                    Position(190.0, 170.0), "2".repeat(32), c.world)))
                val rebuilt = tick().units.single { it.unitId == "constructed:p1:${"2".repeat(32)}" }
                assertTrue(rebuilt.alive)
                assertEquals(Position(180.0, 160.0), rebuilt.position)
                assertEquals(expectedBalance - 500.0, c.observePlayer(c.world, "p1").credits)
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
        return base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground },
            BlockingGrid(40, 25, BooleanArray(1000))), players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
                UnitSpawn("enemy", "p2", "c_tank", Position(300.0, 160.0), 270.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "enemy" to 2L), 3L))
    }
}
