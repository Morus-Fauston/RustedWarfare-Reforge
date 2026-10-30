package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DefenseAfterCenterDeathTest {
    @Test fun real_center_defense_and_death_leave_surviving_income_factory_and_builder_able_to_produce_and_construct_both_defenses() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        var step = 0L
        var centerShots = 0
        var centerDeaths = 0
        fun tick(): Observation {
            val view = c.advance(Step(step++, 1.0, "sim-tick"))
            centerShots += view.events.count { it.kind == "projectile-fired" && it.unitId == "h" }
            centerDeaths += view.events.count { it.kind == "unit-died" && it.unitId == "h" }
            return view
        }
        try {
            while (c.observe().units.single { it.unitId == "h" }.alive && step < 10000L) tick()
            assertFalse(c.observe().units.single { it.unitId == "h" }.alive)
            assertTrue(centerShots > 0)
            assertEquals(1, centerDeaths)
            assertTrue(c.observe().units.any { it.ownerPlayerId == "p2" && it.health!! < it.maxHealth!! })
            val balance = c.observePlayer(c.world, "p1").credits!!
            repeat(40) { tick() }
            assertEquals(balance + 2.0, c.observePlayer(c.world, "p1").credits, "Only the surviving fabricator continues income")
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("builder-after-death", "p1", "factory", "builder", "1".repeat(32), c.world)))
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("tank-after-death", "p1", "factory", "c_tank", "2".repeat(32), c.world)))
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("turret-after-death", "p1", "worker", "c_turret_t1",
                Position(1190.0, 610.0), "3".repeat(32), c.world)))
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("shield-after-death", "p1", "worker", "laserDefence",
                Position(1190.0, 750.0), "4".repeat(32), c.world, append = true)))
            var creations = 0
            var completions = 0
            var produced = 0
            val deadline = step + 5000L
            fun finished(): Boolean = c.observe().production!!.units.size == 2 && c.observe().units.count {
                it.typeId in setOf("c_turret_t1", "laserDefence") && it.ownerPlayerId == "p1" && it.constructionProgress == 1f
            } == 2
            while (!finished() && step < deadline) {
                val frame = tick()
                creations += frame.events.count { it.kind == "construction-created" }
                completions += frame.events.count { it.kind == "construction-completed" }
                produced += frame.events.count { it.kind == "unit-produced" }
            }
            assertTrue(finished())
            assertEquals(2, creations)
            assertEquals(2, completions)
            assertEquals(2, produced)
            val producedIds = c.observe().production!!.units.map { it.unitId }.toSet()
            assertEquals(setOf("builder", "c_tank"), c.observe().units.filter { it.unitId in producedIds }.map { it.typeId }.toSet())
            assertTrue(c.observe().production!!.units.all { it.producerId == "factory" })
            assertEquals(1, centerDeaths)
            assertEquals(1, c.observe().units.count { it.typeId == "commandCenter" })
            assertTrue(c.observe().units.filter { it.typeId in setOf("c_turret_t1", "laserDefence") }.all { it.alive })
            assertTrue(c.observePlayer(c.world, "p1").credits!! > 0.0)
            val after = c.observePlayer(c.world, "p1").credits!!
            repeat(40) { tick() }
            assertEquals(after + 2.0, c.observePlayer(c.world, "p1").credits)
        } finally { c.close() }
    }

    private fun scene(): Scenario {
        val root = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/construction") }
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input"))
        val base = ControlledScenario.load(root)
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        val units = listOf(UnitSpawn("h", "p1", "commandCenter", Position(110.0, 210.0), 0.0),
            UnitSpawn("worker", "p1", "builder", Position(1120.0, 610.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(1310.0, 610.0), 0.0),
            UnitSpawn("income", "p1", "fabricatorT1", Position(1010.0, 610.0), 0.0)) +
            (1..12).map { i -> UnitSpawn("z" + i.toString().padStart(2, '0'), "p2", "c_tank",
                Position(190.0 + ((i - 1) / 6) * 22.0, 150.0 + ((i - 1) % 6) * 22.0), 270.0) }
        return base.copy(map = MapDefinition(80, 40, 20, 20, base.map.tileSets, IntArray(3200) { ground },
            BlockingGrid(80, 40, BooleanArray(3200))), units = units,
            economyContract = EconomyContract(mapOf("p1" to 4000L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, units.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), 17L))
    }
}
