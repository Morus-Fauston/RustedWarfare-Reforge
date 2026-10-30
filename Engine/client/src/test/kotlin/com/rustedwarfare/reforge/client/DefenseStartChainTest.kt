package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DefenseStartChainTest {
    @Test fun the_single_center_with_1750_earns_income_and_produces_the_worker_that_really_builds_both_defenses() {
        val root = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/construction") }
        ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input"))
        val base = ControlledScenario.load(root)
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        // Keep the default players, center/enemies and 1750 balance; use explicit clear ground for this chain.
        val scene = base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground },
            BlockingGrid(40, 25, BooleanArray(1000))))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val start = c.observePlayer(c.world, "p1")
            assertEquals(1750.0, start.credits)
            assertEquals(listOf("commandCenter"), start.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            var step = 0L
            val created = mutableListOf<String?>()
            val completed = mutableListOf<String?>()
            fun tick(): Observation {
                val frame = c.advance(Step(step++, 1.0, "sim-tick"))
                created += frame.events.filter { it.kind == "construction-created" }.map { it.unitId }
                completed += frame.events.filter { it.kind == "construction-completed" }.map { it.unitId }
                return frame
            }
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "h", "builder", "1".repeat(32), c.world)))
            while (c.observe().units.none { it.ownerPlayerId == "p1" && it.typeId == "builder" } && step < 700L) tick()
            val worker = c.observe().units.single { it.ownerPlayerId == "p1" && it.typeId == "builder" }
            assertEquals("h", c.observe().production!!.units.single { it.unitId == worker.unitId }.producerId)
            assertTrue(c.observePlayer(c.world, "p1").credits!! > 1250.0, "The original center must already have generated real income")
            for ((index, type) in listOf("c_turret_t1", "laserDefence").withIndex()) {
                val target = if (index == 0) Position(190.0, 170.0) else Position(270.0, 170.0)
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("build-$index", "p1", worker.unitId, type,
                    target, (index + 2).toString().repeat(32), c.world)))
                while (c.observe().units.none { it.typeId == type && it.constructionProgress == 1f } && step < 7000L) tick()
                val building = c.observe().units.single { it.typeId == type }
                assertEquals(1f, building.constructionProgress)
                assertTrue(building.alive)
                assertEquals("p1", building.ownerPlayerId)
                assertEquals(if (index == 0) Position(180.0, 160.0) else Position(260.0, 160.0), building.position)
            }
            assertEquals(2, created.size)
            assertEquals(created.toSet(), completed.toSet())
            assertEquals(2, completed.size)
            val owner = c.observePlayer(c.world, "p1")
            assertEquals(setOf("commandCenter", "builder", "c_turret_t1", "laserDefence"),
                owner.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId }.toSet())
            assertTrue(owner.credits!! > 0.0)
        } finally { c.close() }
    }
}
