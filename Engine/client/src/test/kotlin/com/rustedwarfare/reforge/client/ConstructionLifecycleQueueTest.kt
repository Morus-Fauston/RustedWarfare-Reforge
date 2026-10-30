package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConstructionLifecycleQueueTest {
    @Test fun a_combat_dead_target_stays_in_the_world_while_each_live_worker_releases_only_its_head_for_the_next_update() {
        val spawns = listOf(UnitSpawn("a", "p1", "builder", Position(380.0, 170.0), 90.0),
            UnitSpawn("b", "p1", "builder", Position(380.0, 210.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(450.0, 170.0), 0.0)) +
            listOf(Position(570.0, 150.0), Position(570.0, 190.0), Position(550.0, 110.0), Position(550.0, 230.0))
                .mapIndexed { index, position -> UnitSpawn("gun-$index", "p2", "c_tank", position, 270.0) }
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(spawns))).context
        var step = 0L
        fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
        try {
            for (i in 0..3) assertIs<ActionResult.Accepted>(c.submit(AttackAction("attack-$i", "p2", "gun-$i", "factory", c.world)))
            while (c.observe().units.single { it.unitId == "factory" }.health!! >= 1190.0 && step < 300) tick()
            assertTrue(c.observe().units.single { it.unitId == "factory" }.health!! in 1.0..<1190.0)
            for (worker in listOf("a", "b")) {
                assertIs<ActionResult.Accepted>(c.submit(RepairBuildingAction("repair-$worker", "p1", worker, "factory", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(PatrolAction("tail-$worker", "p1", worker, Position(650.0, 350.0), true, c.world)))
            }
            while (c.observe().units.single { it.unitId == "factory" }.alive && step < 2400) tick()
            val dead = c.observe()
            assertFalse(dead.units.single { it.unitId == "factory" }.alive)
            assertEquals(emptyList(), dead.reclaimedUnitIds)
            for (worker in listOf("a", "b")) {
                val unit = dead.units.single { it.unitId == worker }
                assertTrue(unit.alive)
                assertEquals(listOf(UnitTaskKind.CONSTRUCT, UnitTaskKind.PATROL), unit.tasks.map { it.kind })
            }
            val popped = tick()
            for (worker in listOf("a", "b")) {
                val before = dead.units.single { it.unitId == worker }
                val after = popped.units.single { it.unitId == worker }
                assertEquals(before.tasks.drop(1), after.tasks)
                assertEquals(before.position, after.position)
                assertNull(after.moveTarget)
            }
            val started = tick()
            assertTrue(started.units.filter { it.unitId in setOf("a", "b") }.all {
                it.tasks.single().kind == UnitTaskKind.PATROL && it.moveTarget != null
            })
            assertEquals(1750.0, c.observePlayer(c.world, "p1").credits)
        } finally { c.close() }
    }

    @Test fun a_workers_real_combat_death_leaves_the_other_worker_and_the_same_paid_project_working() {
        val spawns = listOf(UnitSpawn("a", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("b", "p1", "builder", Position(120.0, 210.0), 90.0),
            UnitSpawn("gun", "p2", "c_tank", Position(240.0, 170.0), 270.0))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(spawns))).context
        var step = 0L
        fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
        try {
            for ((i, worker) in listOf("a", "b").withIndex()) assertIs<ActionResult.Accepted>(c.submit(
                BuildAction("build-$worker", "p1", worker, "landFactory", Position(190.0, 170.0), i.toString().repeat(32), c.world)))
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("kill-a", "p2", "gun", "a", c.world)))
            tick()
            val project = c.observe().units.single { it.typeId == "landFactory" }.unitId
            assertEquals(1050.0, c.observePlayer(c.world, "p1").credits)
            while (c.observe().units.single { it.unitId == "a" }.alive && step < 1000) tick()
            val death = c.observe()
            assertFalse(death.units.single { it.unitId == "a" }.alive)
            assertTrue(death.units.single { it.unitId == "a" }.tasks.isEmpty())
            val other = death.units.single { it.unitId == "b" }
            assertTrue(other.alive)
            assertEquals(project, other.tasks.single().targetUnitId)
            val progress = death.units.single { it.unitId == project }.constructionProgress
            assertTrue(progress in 0f..<1f)
            tick(); tick()
            assertTrue(c.observe().units.single { it.unitId == project }.constructionProgress > progress)
            assertEquals(1, c.observe().units.count { it.typeId == "landFactory" })
            assertEquals(1050.0, c.observePlayer(c.world, "p1").credits)
        } finally { c.close() }
    }

    private fun scene(spawns: List<UnitSpawn>): Scenario {
        val repo = System.getProperty("repo.root")?.let { Path.of(it) } ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val base = ControlledScenario.load((System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/queue") } ?: repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-006/20261029-implementation-01/input")))
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        return base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground }, BlockingGrid(40, 25, BooleanArray(1000))),
            units = spawns, players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            combatContract = CombatContract(1, -1, spawns.mapIndexed { i, s -> s.unitId to i + 1L }.toMap(), spawns.size + 1L))
    }
}
