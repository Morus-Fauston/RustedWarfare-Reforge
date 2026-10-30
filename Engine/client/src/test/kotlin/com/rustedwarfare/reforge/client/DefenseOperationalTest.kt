package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DefenseOperationalTest {
    @Test fun a_really_constructed_turret_and_each_existing_upgrade_branch_fire_without_replacing_the_building_identity() {
        for ((branch, expectedType) in listOf(null to "c_turret_t1", "upgrade_gun" to "c_turret_t2_gun",
            "upgrade_artillery" to "c_turret_t1_artillery")) {
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
            try {
                var step = 0L
                fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("build", "p1", "worker", "c_turret_t1",
                    Position(190.0, 170.0), "1".repeat(32), c.world)))
                val born = tick().units.single { it.typeId == "c_turret_t1" }
                assertEquals(0f, born.constructionProgress)
                while (c.observe().units.single { it.unitId == born.unitId }.constructionProgress < 1f && step < 3000L) tick()
                val complete = c.observe().units.single { it.unitId == born.unitId }
                assertEquals(1f, complete.constructionProgress)
                assertEquals(700.0, complete.health)
                assertIs<ActionResult.Accepted>(c.submit(StopAction("stop-worker", "p1", "worker", c.world)))
                if (branch != null) {
                    assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("upgrade", "p1", born.unitId, branch, c.world)))
                    while (c.observe().units.single { it.unitId == born.unitId }.typeId != expectedType && step < 6500L) tick()
                }
                val ready = c.observe().units.single { it.unitId == born.unitId }
                assertEquals(expectedType, ready.typeId)
                assertEquals("p1", ready.ownerPlayerId)
                assertEquals(born.position, ready.position)
                assertEquals(1f, ready.constructionProgress)
                assertEquals(ready.maxHealth, ready.health, "A full-health building must retain its full HP ratio")
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("approach", "p2", "enemy", Position(300.0, 160.0), c.world)))
                var ownShots = 0
                var damaged = false
                repeat(900) {
                    val frame = tick()
                    ownShots += frame.events.count { it.kind == "projectile-fired" && it.unitId == born.unitId }
                    damaged = damaged || frame.units.single { it.unitId == "enemy" }.health!! < 210.0
                }
                assertTrue(ownShots > 0, "$expectedType must fire its ordinary weapon after construction/upgrade")
                assertTrue(damaged, "$expectedType must cause a real projectile hit")
            } finally { c.close() }
        }
    }

    @Test fun a_really_constructed_shield_intercepts_enemy_fire_and_recharges_after_the_shooter_withdraws() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("build", "p1", "worker", "laserDefence",
                Position(190.0, 170.0), "1".repeat(32), c.world)))
            val born = tick().units.single { it.typeId == "laserDefence" }
            assertEquals(0f, born.constructionProgress)
            while (c.observe().units.single { it.unitId == born.unitId }.constructionProgress < 1f && step < 2500L) tick()
            assertEquals(1f, c.observe().units.single { it.unitId == born.unitId }.constructionProgress)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("stop-worker", "p1", "worker", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("approach", "p2", "enemy", Position(300.0, 160.0), c.world)))
            var enemyShots = 0
            var intercepted = false
            while (!intercepted && step < 4000L) {
                val frame = tick()
                enemyShots += frame.events.count { it.kind == "projectile-fired" && it.unitId == "enemy" }
                intercepted = frame.projectileEvents.any { it.kind == "deflected" && it.interceptorUnitId == born.unitId }
            }
            assertTrue(enemyShots > 0)
            assertTrue(intercepted, "The new shield must intercept a projectile fired by the ordinary enemy tank")
            val spentEnergy = c.observePlayer(c.world, "p1").units.single { it.unitId == born.unitId }.pointDefense!!.energy
            assertTrue(spentEnergy < 1f)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("withdraw", "p2", "enemy", Position(650.0, 160.0), c.world)))
            repeat(650) { tick() }
            val before = c.observePlayer(c.world, "p1").units.single { it.unitId == born.unitId }
            assertTrue(before.alive)
            assertEquals(1f, before.pointDefense!!.energy, "The shield must fully recover after the ordinary withdrawal")
            assertTrue(before.pointDefense!!.energy > spentEnergy)
            var additionalShots = 0
            repeat(100) { additionalShots += tick().events.count { it.kind == "projectile-fired" && it.unitId == "enemy" } }
            val after = c.observePlayer(c.world, "p1").units.single { it.unitId == born.unitId }
            assertEquals(0, additionalShots)
            assertEquals(1f, after.pointDefense!!.energy)
        } finally { c.close() }
    }

    private fun scene(): Scenario {
        val root = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/construction") }
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input"))
        val base = ControlledScenario.load(root)
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        return base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground },
            BlockingGrid(40, 25, BooleanArray(1000))),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0),
                UnitSpawn("enemy", "p2", "c_tank", Position(650.0, 160.0), 270.0)),
            economyContract = EconomyContract(mapOf("p1" to 3500L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "enemy" to 2L), 3L))
    }
}
