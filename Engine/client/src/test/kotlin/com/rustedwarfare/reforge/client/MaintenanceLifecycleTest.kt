package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MaintenanceLifecycleTest {
    @Test fun a_builders_real_combat_death_preserves_the_partially_reclaimed_building_for_another_workers_free_repair() {
        val base = base()
        val spawns = listOf(UnitSpawn("a", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("b", "p1", "builder", Position(120.0, 210.0), 90.0),
            UnitSpawn("factory", "p1", "landFactory", Position(190.0, 170.0), 0.0),
            UnitSpawn("gun", "p2", "c_tank", Position(120.0, 50.0), 180.0))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(units = spawns,
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            combatContract = CombatContract(1, -1, spawns.mapIndexed { i, s -> s.unitId to i + 1L }.toMap(), 5L)))).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("kill-worker", "p2", "gun", "a", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("reclaim", "p1", "a", "factory", c.world)))
            while (c.observe().units.single { it.unitId == "a" }.alive && step < 700) tick()
            assertFalse(c.observe().units.single { it.unitId == "a" }.alive)
            val damaged = c.observe().units.single { it.unitId == "factory" }.health!!
            assertTrue(damaged in 1.0..<1200.0)
            repeat(20) { tick() }
            assertEquals(damaged, c.observe().units.single { it.unitId == "factory" }.health)
            assertEquals(1750.0, c.observePlayer(c.world, "p1").credits)
            assertIs<ActionResult.Accepted>(c.submit(RepairBuildingAction("take-over", "p1", "b", "factory", c.world)))
            while (c.observe().units.single { it.unitId == "factory" }.health!! < 1200.0 && step < 3700) tick()
            assertEquals(1200.0, c.observe().units.single { it.unitId == "factory" }.health)
            assertEquals(1750.0, c.observePlayer(c.world, "p1").credits)
            assertEquals(emptyList(), c.observe().reclaimedUnitIds)
        } finally { c.close() }
    }

    @Test fun every_loaded_ordinary_tier_can_be_maintained_and_returns_its_current_base_price_without_damage_or_a_corpse() {
        val refunds = mapOf("commandCenter" to 2400.000035762787, "landFactory" to 560.0000083446503,
            "extractorT1" to 560.0000083446503, "extractorT2" to 1680.0000250339508,
            "extractorT3" to 4880.000072717667, "fabricatorT1" to 1760.0000262260437,
            "fabricatorT2" to 5280.000078678131, "fabricatorT3" to 11280.000168085098)
        val base = base()
        assertEquals(checkNotNull(base.maintenanceContract).basePrices.keys, refunds.keys)
        for ((type, refund) in refunds) {
            val pos = if (type.startsWith("extractor")) Position(190.0, 290.0) else Position(190.0, 170.0)
            val spawns = listOf(UnitSpawn("a-worker", "p1", "builder", Position(120.0, pos.y), 90.0),
                UnitSpawn("z-target", "p1", type, pos, 0.0))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(units = spawns,
                combatContract = CombatContract(1, -1, mapOf("a-worker" to 1L, "z-target" to 2L), 3L)))).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(RepairBuildingAction("full", "p1", "a-worker", "z-target", c.world)))
                c.advance(Step(0, 1.0, "sim-tick"))
                assertTrue(c.observe().units.single { it.unitId == "a-worker" }.tasks.isEmpty())
                assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("reclaim", "p1", "a-worker", "z-target", c.world)))
                var terminal = false
                for (step in 1L..10000L) {
                    val before = c.observePlayer(c.world, "p1").credits!!
                    val view = c.advance(Step(step, 1.0, "sim-tick"))
                    assertTrue(view.projectiles.isEmpty(), type)
                    assertTrue(view.events.none { it.kind in setOf("unit-damaged", "unit-died") }, type)
                    if (view.units.none { it.unitId == "z-target" }) {
                        terminal = true
                        assertEquals(before + refund, c.observePlayer(c.world, "p1").credits, type)
                        assertEquals(listOf("z-target"), view.reclaimedUnitIds, type)
                        break
                    }
                }
                assertTrue(terminal, type)
            } finally { c.close() }
        }
    }

    @Test fun reclaim_refunds_only_unfinished_factory_orders_and_keeps_the_real_produced_unit_and_releases_the_site() {
        val scene = base().copy(units = listOf(UnitSpawn("a-worker", "p1", "builder", Position(120.0, 170.0), 90.0),
            UnitSpawn("z-factory", "p1", "landFactory", Position(190.0, 170.0), 0.0)),
            combatContract = CombatContract(1, -1, mapOf("a-worker" to 1L, "z-factory" to 2L), 3L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var step = 0L
            fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("first", "p1", "z-factory", "builder", "1".repeat(32), c.world)))
            while (c.observe().production!!.units.isEmpty() && step < 700) tick()
            val produced = c.observe().production!!.units.single().unitId
            assertEquals(1250.0, c.observePlayer(c.world, "p1").credits)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("clear-exit", "p1", produced, Position(310.0, 170.0), c.world)))
            assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("reclaim", "p1", "a-worker", "z-factory", c.world)))
            repeat(1000) { tick() }
            assertTrue(c.observe().units.single { it.unitId == "z-factory" }.health!! in 1.0..<250.0)
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("pending-builder", "p1", "z-factory", "builder", "2".repeat(32), c.world)))
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("pending-tank", "p1", "z-factory", "c_tank", "3".repeat(32), c.world)))
            assertEquals(400.0, c.observePlayer(c.world, "p1").credits)
            while (c.observe().units.any { it.unitId == "z-factory" } && step < 2200) tick()
            assertTrue(c.observe().units.none { it.unitId == "z-factory" })
            assertEquals(1810.0000083446503, c.observePlayer(c.world, "p1").credits)
            assertEquals(listOf("z-factory"), c.observe().production!!.units.map { it.producerId })
            assertTrue(c.observe().units.single { it.unitId == produced }.alive)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("after-source", "p1", produced, Position(350.0, 210.0), c.world)))
            tick()
            assertIs<ActionResult.Accepted>(c.submit(BuildAction("reuse-site", "p1", "a-worker", "landFactory",
                Position(190.0, 170.0), "4".repeat(32), c.world)))
            tick()
            assertEquals(1, c.observe().units.count { it.typeId == "landFactory" })
            assertEquals(Position(190.0, 170.0), c.observe().units.single { it.typeId == "landFactory" }.position)
            assertEquals(1110.0000083446503, c.observePlayer(c.world, "p1").credits)
        } finally { c.close() }
    }

    @Test fun a_tier_two_reclaim_pays_the_current_base_price_and_separately_returns_the_unfinished_tier_three_queue() {
        val base = base()
        val scene = base.copy(units = listOf(UnitSpawn("a-worker", "p1", "builder", Position(120.0, 290.0), 90.0),
            UnitSpawn("z-extractor", "p1", "extractorT2", Position(190.0, 290.0), 0.0)),
            economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, mapOf("a-worker" to 1L, "z-extractor" to 2L), 3L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("pending", "p1", "z-extractor", "upgrade_t3", c.world)))
            assertEquals(6000.0, c.observePlayer(c.world, "p1").credits)
            assertIs<ActionResult.Accepted>(c.submit(ReclaimBuildingAction("reclaim", "p1", "a-worker", "z-extractor", c.world)))
            var terminal = false
            for (step in 0L..2500L) {
                val before = c.observePlayer(c.world, "p1").credits!!
                val view = c.advance(Step(step, 1.0, "sim-tick"))
                if (view.units.none { it.unitId == "z-extractor" }) {
                    terminal = true
                    assertEquals(before + 5680.000025033951, c.observePlayer(c.world, "p1").credits)
                    assertTrue(c.observePlayer(c.world, "p1").upgradeQueues.none { it.unitId == "z-extractor" })
                    assertTrue(view.events.none { it.kind == "upgrade-completed" })
                    break
                }
                assertEquals("extractorT2", view.units.single { it.unitId == "z-extractor" }.typeId)
            }
            assertTrue(terminal)
        } finally { c.close() }
    }

    private fun base(): Scenario = ControlledScenario.load(generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-005/20261029-implementation-01/input"))
}
